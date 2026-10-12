/*
 * Copyright 2026, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.prov.renderers

import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

import com.google.common.hash.HashCode
import groovy.json.JsonSlurper
import nextflow.NextflowMeta
import nextflow.Session
import nextflow.config.Manifest
import nextflow.file.FileHolder
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import nextflow.prov.ProvWrrocConfig
import nextflow.script.BaseScript
import nextflow.script.ProcessDef
import nextflow.script.ScriptBinding
import nextflow.script.ScriptMeta
import nextflow.script.ScriptType
import nextflow.script.WorkflowMetadata
import nextflow.script.params.FileOutParam
import nextflow.util.Duration
import nextflow.util.MemoryUnit
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class WrrocRendererTest extends Specification {

    @TempDir
    Path root

    Path projectDir
    Path workDir
    Path crateDir

    int taskCount = 0

    def setup() {
        projectDir = Files.createDirectories(root.resolve('project'))
        workDir = Files.createDirectories(root.resolve('work'))
        crateDir = Files.createDirectories(root.resolve('results'))
        projectDir.resolve('main.nf').text = 'workflow {}'
    }

    def cleanup() {
        ScriptMeta.reset()
    }

    // -- fixtures

    /**
     * Register a script with the given process definitions.
     *
     * @param scriptPath
     * @param processNames
     */
    BaseScript script(Path scriptPath, List<String> processNames) {
        Files.createDirectories(scriptPath.parent)
        if( !Files.exists(scriptPath) )
            scriptPath.text = ''
        final owner = Mock(BaseScript)
        final defs = processNames.collect { name ->
            Mock(ProcessDef) {
                getName() >> name
                getBaseName() >> name
                getOwner() >> owner
            }
        }
        final meta = Mock(ScriptMeta) {
            getDefinitions() >> defs
            getScriptPath() >> scriptPath
            getModuleDir() >> scriptPath.parent
        }
        ScriptMeta.REGISTRY.put(owner, meta)
        return owner
    }

    TaskProcessor processor(String name, BaseScript owner) {
        final id = ++taskCount
        Mock(TaskProcessor) {
            getName() >> name
            getOwnerScript() >> owner
            getId() >> id
        }
    }

    /**
     * Create a task with the given input files and output file names.
     *
     * @param processor
     * @param name
     * @param inputs
     * @param outputs
     * @param exitStatus
     */
    TaskRun task(Map opts = [:], TaskProcessor processor, String name) {
        final hash = HashCode.fromLong(++taskCount)
        final taskDir = Files.createDirectories(workDir.resolve(hash.toString()))
        final inputs = (opts.inputs ?: []) as List<Path>
        final outputs = ((opts.outputs ?: []) as List<String>).collect { fileName ->
            final target = taskDir.resolve(fileName)
            target.text = fileName
            target
        }
        final exitStatus = opts.containsKey('exitStatus') ? opts.exitStatus : 0
        final type = opts.type ?: ScriptType.SCRIPTLET
        Mock(TaskRun) {
            getProcessor() >> processor
            getName() >> name
            getHash() >> hash
            getWorkDir() >> taskDir
            getInputFiles() >> inputs.collect { source -> new FileHolder(source) }
            getOutputsByType(FileOutParam) >> [(Mock(FileOutParam)): outputs]
            getExitStatus() >> exitStatus
            getType() >> type
            isSuccess() >> (exitStatus == 0)
            getStderr() >> 'oops'
        }
    }

    Session session(Map opts = [:]) {
        final wrrocOpts = [license: 'https://spdx.org/licenses/Apache-2.0'] + (opts.wrroc ?: [:])
        final metadata = Mock(WorkflowMetadata) {
            getProjectDir() >> projectDir
            getWorkDir() >> workDir
            getScriptFile() >> projectDir.resolve('main.nf')
            getProjectName() >> 'test'
            getManifest() >> new Manifest([name: 'nf-prov-test', description: 'Test pipeline'])
            getStart() >> OffsetDateTime.now()
            getComplete() >> OffsetDateTime.now()
            getNextflow() >> NextflowMeta.instance
        }
        Mock(Session) {
            getConfig() >> [prov: [formats: [wrroc: wrrocOpts]]] + (opts.config ?: [:])
            getParams() >> new ScriptBinding.ParamsMap((opts.params ?: [:]) as Map<String,Object>)
            getWorkflowMetadata() >> metadata
            getWorkDir() >> workDir
            getOutputDir() >> crateDir
            getUniqueId() >> UUID.fromString('00000000-0000-0000-0000-000000000000')
        }
    }

    /**
     * Render a crate and return the graph as a map of entities by id.
     */
    Map<String,Map> render(Map opts = [:], Set<TaskRun> tasks) {
        final renderer = new WrrocRenderer(new ProvWrrocConfig([file: crateDir.resolve('ro-crate-metadata.json').toString()]))
        renderer.render(
            session(opts),
            tasks,
            (opts.workflowOutputs ?: [:]) as Map<String,Path>,
            (opts.publishedFiles ?: [:]) as Map<Path,Path>)
        final crate = new JsonSlurper().parse(crateDir.resolve('ro-crate-metadata.json').toFile()) as Map
        final entities = crate['@graph'] as List<Map>
        final graph = entities.collectEntries { entity -> [entity['@id'], entity] }
        assert graph.size() == entities.size() : 'crate contains duplicate ids'
        return graph
    }

    /**
     * Get all local references (i.e. "#..." or relative paths) in the graph.
     */
    static Set<String> references(Object value) {
        if( value instanceof Map ) {
            if( value.size() == 1 && value.containsKey('@id') )
                return [value['@id'] as String] as Set
            return value.values().collectMany { v -> references(v) } as Set
        }
        if( value instanceof List )
            return value.collectMany { v -> references(v) } as Set
        return [] as Set
    }

    static Set<String> nullKeys(Map<String,Map> graph) {
        graph.values().collectMany { entity ->
            entity.findAll { k, v -> v == null }.collect { k, v -> "${entity['@id']}.${k}".toString() }
        } as Set
    }

    // -- tests

    def 'should render a self-consistent crate' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO', 'BAR'])
        final foo = processor('FOO', owner)
        final bar = processor('BAR', owner)
        final input = Files.createFile(root.resolve('input.txt'))
        final t1 = task(foo, 'FOO (1)', inputs: [input], outputs: ['foo.txt'])
        final fooOut = t1.workDir.resolve('foo.txt')
        final t2 = task(bar, 'BAR (1)', inputs: [fooOut], outputs: ['bar.txt'])
        final barOut = t2.workDir.resolve('bar.txt')
        final published = Files.createFile(crateDir.resolve('bar.txt'))

        when:
        final graph = render(
            [t1, t2] as Set,
            wrroc: [agent: [name: 'Alice', orcid: 'https://orcid.org/0000-0000-0000-0000']],
            params: [input: input.toString()],
            workflowOutputs: [bar: published],
            publishedFiles: [(barOut): published])

        then:
        final dangling = references(graph.values() as List).findAll { id -> !id.startsWith('http') && !graph.containsKey(id) }
        dangling.isEmpty()
        nullKeys(graph).isEmpty()

        and:
        graph['ro-crate-metadata.json'].about == ['@id': './']
        graph['./'].mainEntity == ['@id': 'main.nf']
        graph['./'].license == 'https://spdx.org/licenses/Apache-2.0'
        graph['main.nf'].step as Set == [['@id': '#process-step/FOO'], ['@id': '#process-step/BAR']] as Set
        graph['nextflow.config']

        and:
        graph["#task/${t1.hash}"].instrument == ['@id': '#module/FOO']
        graph["#task/${t1.hash}"].object == [['@id': input.toUri().toString()]]
        graph["#task/${t1.hash}"].result == [['@id': "#task/${t1.hash}/foo.txt"]]
        graph["#task/${t2.hash}"].instrument == ['@id': '#module/BAR']
        graph["#task/${t2.hash}"].object == [['@id': "#task/${t1.hash}/foo.txt"]]

        and:
        graph["#publish/${t2.hash}/bar.txt"].object == ['@id': "#task/${t2.hash}/bar.txt"]
        graph["#publish/${t2.hash}/bar.txt"].result == ['@id': 'bar.txt']
        graph['#output/bar/value'].value == 'bar.txt'
    }

    @Unroll
    def 'should identify process definition for #processName' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), defs)
        final t = task(processor(processName, owner), 'task')

        when:
        final graph = render([t] as Set)

        then:
        graph["#task/${t.hash}"].instrument == (moduleId ? ['@id': moduleId] : null)
        graph["#process-step/${processName}"].workExample == (moduleId ? ['@id': moduleId] : null)

        where:
        defs                | processName           | moduleId
        ['FOO']             | 'FOO'                 | '#module/FOO'
        ['FOO']             | 'WF:FOO'              | '#module/FOO'
        ['FOO']             | 'WF:FOO_ALIAS'        | '#module/FOO'
        ['FOO', 'BAR']      | 'BAR'                 | '#module/BAR'
        ['FOO', 'BAR']      | 'OUTER:INNER:BAR'     | '#module/BAR'
        ['FOO', 'BAR']      | 'WF:BAR_ALIAS'        | null
    }

    def 'should render module tools from nf-core meta.yml' () {
        given:
        final modulePath = projectDir.resolve('modules/fastqc/main.nf')
        final owner = script(modulePath, ['FASTQC'])
        modulePath.parent.resolve('meta.yml').text = '''\
            name: fastqc
            tools:
              - fastqc:
                  description: A quality control tool
            '''.stripIndent()
        final t = task(processor('WF:FASTQC', owner), 'FASTQC (1)')

        when:
        final graph = render([t] as Set)

        then:
        graph['#module/FASTQC'].name == 'fastqc'
        graph['#module/FASTQC'].hasPart == [['@id': '#module/FASTQC/fastqc']]
        graph['#module/FASTQC'].url == modulePath.toUri().toString()
        graph['#module/FASTQC/fastqc'].description == 'A quality control tool'
    }

    def 'should record failed task' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final t = task(processor('FOO', owner), 'FOO (1)', exitStatus: 1)

        when:
        final graph = render([t] as Set)

        then:
        graph["#task/${t.hash}"].actionStatus == 'http://schema.org/FailedActionStatus'
        graph["#task/${t.hash}"].error == 'oops'
    }

    def 'should record native task as completed' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final t = task(processor('FOO', owner), 'FOO (1)', type: ScriptType.GROOVY, exitStatus: Integer.MAX_VALUE, outputs: ['foo.txt'])

        when:
        final graph = render([t] as Set)

        then:
        graph["#task/${t.hash}"].actionStatus == 'http://schema.org/CompletedActionStatus'
        !graph["#task/${t.hash}"].containsKey('error')
        graph["#task/${t.hash}"].result == [['@id': "#task/${t.hash}/foo.txt"]]
    }

    def 'should record tmp inputs' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final tmpFile = Files.createDirectories(workDir.resolve('tmp/ab')).resolve('collected.txt')
        tmpFile.text = 'hello'
        final t = task(processor('FOO', owner), 'FOO (1)', inputs: [tmpFile])

        when:
        final graph = render([t] as Set)

        then:
        graph['#tmp/collected.txt']['@type'] == 'CreativeWork'
        graph["#task/${t.hash}"].object == [['@id': '#tmp/collected.txt']]
        !graph.containsKey(tmpFile.toUri().toString())
    }

    def 'should copy local input files specified by params into crate' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final input = root.resolve('samples.csv')
        input.text = 'id\n1\n'
        final t = task(processor('FOO', owner), 'FOO (1)', inputs: [input])

        when:
        final graph = render([t] as Set, params: [input: input])

        then:
        Files.exists(crateDir.resolve('samples.csv'))
        graph['samples.csv']['@type'] == 'File'
        graph['#param/input'].additionalType == 'File'
        graph["#task/${t.hash}"].object == [['@id': 'samples.csv']]
    }

    def 'should exclude published files outside of crate directory' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final t = task(processor('FOO', owner), 'FOO (1)', outputs: ['foo.txt'])
        final outside = Files.createFile(root.resolve('foo.txt'))

        when:
        final graph = render([t] as Set, publishedFiles: [(t.workDir.resolve('foo.txt')): outside])

        then:
        !graph['./'].hasPart.contains(['@id': '../foo.txt'])
        !graph.values().any { entity -> entity.name == 'publish' && entity.result == ['@id': '../foo.txt'] }
    }

    def 'should include pipeline_info files in crate directory' () {
        given:
        final infoDir = crateDir.resolve('pipeline_info')
        final config = [
            report  : [enabled: true, file: infoDir.resolve('report.html').toString()],
            timeline: [enabled: true, file: infoDir.resolve('timeline.html').toString()],
            dag     : [enabled: true, file: infoDir.resolve('dag.dot').toString()],
            trace   : [enabled: true, file: infoDir.resolve('trace.txt').toString()],
        ]

        when:
        final graph = render([] as Set, config: config)

        then:
        graph['./'].hasPart.containsAll([
            ['@id': 'pipeline_info/report.html'],
            ['@id': 'pipeline_info/timeline.html'],
            ['@id': 'pipeline_info/dag.dot'],
            ['@id': 'pipeline_info/trace.txt'],
        ])
        graph['pipeline_info/report.html'] == [
            '@id': 'pipeline_info/report.html',
            '@type': 'File',
            name: 'report.html',
            description: 'Nextflow execution report',
            encodingFormat: 'text/html'
        ]
        graph['pipeline_info/timeline.html'].description == 'Nextflow execution timeline'
        graph['pipeline_info/dag.dot'].description == 'Nextflow workflow DAG'
        !graph['pipeline_info/dag.dot'].containsKey('encodingFormat')
        graph['pipeline_info/trace.txt'].description == 'Nextflow execution trace'
        graph['pipeline_info/trace.txt'].encodingFormat == 'text/tab-separated-values'
    }

    def 'should exclude pipeline_info files that are disabled, unnamed, or outside of crate directory' () {
        given:
        final config = [
            report  : [enabled: false, file: crateDir.resolve('report.html').toString()],
            timeline: [enabled: true],
            dag     : [enabled: true, file: root.resolve('dag.html').toString()],
            trace   : [enabled: true, file: 'pipeline_info/trace.txt'],
        ]

        when:
        final graph = render([] as Set, config: config)

        then:
        graph['./'].hasPart == [['@id': 'main.nf'], ['@id': 'https://spdx.org/licenses/Apache-2.0'], ['@id': 'nextflow.config']]
    }

    def 'should render agent, organization and contact points' () {
        given:
        final wrroc = [
            agent: [name: 'Alice', orcid: 'https://orcid.org/0000-0000-0000-0001', email: 'alice@example.com'],
            organization: [name: 'Example', ror: 'https://ror.org/000000000', phone: '+1-555-0100'],
            publisher: 'https://ror.org/000000000'
        ]

        when:
        final graph = render([] as Set, wrroc: wrroc)

        then:
        graph['https://orcid.org/0000-0000-0000-0001'] == [
            '@id': 'https://orcid.org/0000-0000-0000-0001',
            '@type': 'Person',
            name: 'Alice',
            contactPoint: ['@id': 'mailto:alice@example.com'],
            affiliation: ['@id': 'https://ror.org/000000000']
        ]
        graph['https://ror.org/000000000'].contactPoint == ['@id': '+1-555-0100']
        graph['mailto:alice@example.com'].url == 'https://orcid.org/0000-0000-0000-0001'
        graph['+1-555-0100'].url == 'https://ror.org/000000000'
        graph['./'].author == ['@id': 'https://orcid.org/0000-0000-0000-0001']
        graph['./'].publisher == ['@id': 'https://ror.org/000000000']
    }

    def 'should ignore publisher that is not the agent or organization' () {
        when:
        final graph = render([] as Set, wrroc: [agent: [name: 'Alice'], publisher: 'https://example.com'])

        then:
        graph['agent-1'].name == 'Alice'
        !graph['./'].containsKey('publisher')
    }

    def 'should render parameters from schema' () {
        given:
        projectDir.resolve('nextflow_schema.json').text = '''
            {
              "$defs": {
                "input_output_options": {
                  "properties": {
                    "outdir": { "type": "string", "format": "directory-path", "description": "Output directory" },
                    "reads": { "type": "string", "format": "file-path" }
                  }
                }
              },
              "properties": {
                "max_cpus": { "type": "integer", "default": 4 }
              }
            }
            '''

        when:
        final graph = render([] as Set, params: [outdir: 'results', reads: 'reads.fq', max_cpus: 2, unset: null])

        then:
        graph['nextflow_schema.json']
        graph['#param/outdir'].additionalType == 'Dataset'
        graph['#param/outdir'].description == 'Output directory'
        graph['#param/reads'].additionalType == 'File'
        graph['#param/max_cpus'].additionalType == 'Number'
        graph['#param/max_cpus'].defaultValue == 4
        graph['#param/max_cpus/value'].value == 2
        !graph.containsKey('#param/unset')
    }

    @Unroll
    def 'should infer parameter type of #value' () {
        expect:
        WrrocRenderer.getParameterType('p', value, schema) == type

        where:
        value                       | schema                                        | type
        true                        | null                                          | 'Boolean'
        1                           | null                                          | 'Number'
        1.5                         | null                                          | 'Number'
        Duration.of('1h')           | null                                          | 'Number'
        MemoryUnit.of('1 GB')       | null                                          | 'Number'
        'hello'                     | null                                          | 'Text'
        [1, 2]                      | null                                          | 'Text'
        [a: 1]                      | null                                          | 'Text'
        new Object()                | null                                          | null
        'x'                         | [type: 'boolean']                             | 'Boolean'
        'x'                         | [type: 'number']                              | 'Number'
        'x'                         | [type: 'string']                              | 'Text'
        'x'                         | [type: 'string', format: 'file-path']         | 'File'
        'x'                         | [type: 'string', format: 'directory-path']    | 'Dataset'
    }

    @Unroll
    def 'should parse parameter schema with #key' () {
        given:
        final schemaPath = root.resolve('schema.json')
        schemaPath.text = """{ "${key}": { "group": { "properties": { "foo": { "type": "string" } } } } }"""

        expect:
        WrrocRenderer.getParameterSchema(schemaPath) == [foo: [type: 'string']]

        where:
        key << ['$defs', 'defs', 'definitions']
    }

    def 'should normalize parameter values' () {
        when:
        final graph = render([] as Set, params: [
            flag: true,
            n: 3,
            list: [1, 2],
            map: [a: 1],
            time: Duration.of('1s'),
            mem: MemoryUnit.of('1 KB'),
        ])

        then:
        graph['#param/flag/value'].value == true
        graph['#param/n/value'].value == 3
        graph['#param/list/value'].value == '[1,2]'
        graph['#param/map/value'].value == '{"a":1}'
        graph['#param/time/value'].value == 1000
        graph['#param/mem/value'].value == 1024
    }

}
