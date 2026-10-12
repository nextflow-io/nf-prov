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

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

import com.google.common.hash.HashCode
import groovy.json.JsonSlurper
import nextflow.NextflowMeta
import nextflow.Session
import nextflow.conda.CondaConfig
import nextflow.config.Manifest
import nextflow.dag.DAG
import nextflow.file.FileHolder
import nextflow.processor.TaskConfig
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
            getConfig() >> new TaskConfig(opts.conda ? [conda: opts.conda] : [:])
            getCondaConfig() >> new CondaConfig([enabled: opts.condaEnabled ?: false], [:])
            isContainerEnabled() >> (opts.container != null)
            getContainer() >> opts.container
        }
    }

    Session session(Map opts = [:]) {
        final wrrocOpts = [license: 'https://spdx.org/licenses/Apache-2.0'] + (opts.wrroc ?: [:])
        final metadata = Mock(WorkflowMetadata) {
            getProjectDir() >> projectDir
            getWorkDir() >> workDir
            getScriptFile() >> projectDir.resolve('main.nf')
            getProjectName() >> 'test'
            getRepository() >> opts.repository
            getCommitId() >> opts.commitId
            getCommandLine() >> opts.commandLine
            getManifest() >> new Manifest([name: 'nf-prov-test', description: 'Test pipeline', version: '1.0.0'] + (opts.manifest ?: [:]))
            getStart() >> OffsetDateTime.now()
            getComplete() >> OffsetDateTime.now()
            getNextflow() >> NextflowMeta.instance
        }
        Mock(Session) {
            getConfig() >> [prov: [formats: [wrroc: wrrocOpts]]]
            getParams() >> new ScriptBinding.ParamsMap((opts.params ?: [:]) as Map<String,Object>)
            getWorkflowMetadata() >> metadata
            getDag() >> (opts.dag ?: new DAG())
            getWorkDir() >> workDir
            getOutputDir() >> (opts.containsKey('outputDir') ? opts.outputDir : crateDir)
            getUniqueId() >> UUID.fromString('00000000-0000-0000-0000-000000000000')
        }
    }

    /**
     * Render a crate and return the graph as a map of entities by id.
     *
     * Every crate is checked for duplicate ids, dangling references
     * and null values.
     */
    Map<String,Map> render(Map opts = [:], Set<TaskRun> tasks) {
        final renderer = new WrrocRenderer(new ProvWrrocConfig([file: crateDir.resolve('ro-crate-metadata.json').toString()]))
        renderer.render(
            session(opts),
            tasks,
            (opts.workflowOutputs ?: [:]) as Map<String,List<Path>>,
            (opts.publishedFiles ?: [:]) as Map<Path,Path>)
        final crate = new JsonSlurper().parse(crateDir.resolve('ro-crate-metadata.json').toFile()) as Map
        final entities = crate['@graph'] as List<Map>
        final graph = entities.collectEntries { entity -> [entity['@id'], entity] }
        assert graph.size() == entities.size() : 'crate contains duplicate ids'
        final dangling = references(entities).findAll { id -> !id.startsWith('http') && !graph.containsKey(id) }
        assert dangling.isEmpty() : "crate contains dangling references: ${dangling}"
        assert nullKeys(graph).isEmpty() : "crate contains null values: ${nullKeys(graph)}"
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

    def 'should render a crate' () {
        given:
        final foo = processor('WF:FOO', script(projectDir.resolve('modules/foo/main.nf'), ['FOO']))
        final bar = processor('WF:BAR', script(projectDir.resolve('modules/bar/main.nf'), ['FOO']))
        final input = Files.createFile(root.resolve('input.txt'))
        final t1 = task(foo, 'FOO (1)', inputs: [input], outputs: ['foo.txt'])
        final fooOut = t1.workDir.resolve('foo.txt')
        final t2 = task(bar, 'BAR (1)', inputs: [fooOut], outputs: ['bar.txt'])
        final barOut = t2.workDir.resolve('bar.txt')
        final published = Files.createFile(crateDir.resolve('bar.txt'))
        projectDir.resolve('README.md').text = '# test'

        when:
        final graph = render(
            [t1, t2] as Set,
            wrroc: [agent: [name: 'Alice', orcid: 'https://orcid.org/0000-0000-0000-0000']],
            params: [input: input.toString()],
            workflowOutputs: [bar: [published]],
            publishedFiles: [(published): barOut])

        then:
        graph['ro-crate-metadata.json'].about == ['@id': './']
        graph['./'].mainEntity == ['@id': 'main.nf']
        graph['./'].license == ['@id': 'https://spdx.org/licenses/Apache-2.0']
        graph['main.nf'].step as Set == [['@id': '#process-step/WF:FOO'], ['@id': '#process-step/WF:BAR']] as Set
        graph['nextflow.config']
        graph['https://spdx.org/licenses/Apache-2.0']['@type'] == 'CreativeWork'
        !graph['./'].hasPart.contains(['@id': 'https://spdx.org/licenses/Apache-2.0'])
        graph['README.md'].about == ['@id': './']
        graph['README.md'].encodingFormat == 'text/markdown'
        graph['main.nf'].version == '1.0.0'
        graph['main.nf'].input == ['@id': '#param/input']
        graph['main.nf'].output == ['@id': '#output/bar']

        and:
        final run = graph['#00000000-0000-0000-0000-000000000000']
        run.startTime ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}/
        run.endTime ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}/
        graph[graph['main.nf#organize'].instrument['@id']].softwareVersion == NextflowMeta.instance.version.toString()

        and:
        graph["#task/${t1.hash}"].instrument == ['@id': '#module/modules/foo/main.nf/FOO']
        graph["#task/${t1.hash}"].object == ['@id': input.toUri().toString()]
        graph["#task/${t1.hash}"].result == ['@id': "#task/${t1.hash}/foo.txt"]
        graph["#task/${t2.hash}"].instrument == ['@id': '#module/modules/bar/main.nf/FOO']
        graph["#task/${t2.hash}"].object == ['@id': "#task/${t1.hash}/foo.txt"]

        and:
        graph["#publish/${t2.hash}/bar.txt"].object == ['@id': "#task/${t2.hash}/bar.txt"]
        graph["#publish/${t2.hash}/bar.txt"].result == ['@id': 'bar.txt']
        graph["#publish/${t2.hash}/bar.txt"].agent == ['@id': 'https://orcid.org/0000-0000-0000-0000']
        graph['bar.txt'].exampleOfWork == ['@id': '#output/bar']
        !graph.containsKey('#output/bar/value')
        graph['#00000000-0000-0000-0000-000000000000'].result == ['@id': 'bar.txt']
    }

    def 'should add workflow outputs that were not published by a task' () {
        given:
        final index = crateDir.resolve('samples.json')
        index.text = '[]'
        final dir = Files.createDirectories(crateDir.resolve('reports'))

        when:
        final graph = render([] as Set, workflowOutputs: [samples: [index], reports: [dir]])

        then:
        graph['samples.json']['@type'] == 'File'
        graph['samples.json'].exampleOfWork == ['@id': '#output/samples']
        graph['reports/']['@type'] == 'Dataset'
        graph['reports/'].exampleOfWork == ['@id': '#output/reports']
        graph['#output/reports'].additionalType == 'Dataset'
        graph['#00000000-0000-0000-0000-000000000000'].result as Set == [['@id': 'samples.json'], ['@id': 'reports/']] as Set
    }

    def 'should render workflow outputs without an output directory' () {
        given:
        final t1 = task(processor('FOO', script(projectDir.resolve('main.nf'), ['FOO'])), 'FOO (1)', outputs: ['foo.txt'])
        final fooOut = t1.workDir.resolve('foo.txt')

        when:
        final graph = render([t1] as Set, outputDir: null, workflowOutputs: [foo: [fooOut]])

        then:
        graph['#output/foo/value'].value == "work/${t1.hash}/foo.txt"
    }

    def 'should link every file of a workflow output' () {
        given:
        final a = Files.createFile(crateDir.resolve('a.txt'))
        final b = Files.createFile(crateDir.resolve('b.txt'))

        when:
        final graph = render([] as Set, workflowOutputs: [txt: [a, b]])

        then:
        graph['#output/txt'].additionalType == 'File'
        !graph['#output/txt'].containsKey('encodingFormat')
        graph['a.txt'].exampleOfWork == ['@id': '#output/txt']
        graph['b.txt'].exampleOfWork == ['@id': '#output/txt']
        !graph.containsKey('#output/txt/value')
    }

    def 'should render manifest authors as creators' () {
        given:
        final contributors = [
            [name: 'Alice', orcid: 'https://orcid.org/0000-0000-0000-0001', contribution: ['author']],
            [name: 'Bob Smith', email: 'bob@example.com', contribution: ['author', 'maintainer']],
            [name: 'Carol', contribution: ['contributor']],
        ]

        when:
        final graph = render([] as Set,
            wrroc: [agent: [name: 'Alice', orcid: 'https://orcid.org/0000-0000-0000-0001']],
            manifest: [contributors: contributors])

        then:
        graph['main.nf'].creator == [['@id': 'https://orcid.org/0000-0000-0000-0001'], ['@id': '#Bob_Smith']]
        graph['https://orcid.org/0000-0000-0000-0001'].name == 'Alice'
        graph['#Bob_Smith'] == ['@id': '#Bob_Smith', '@type': 'Person', name: 'Bob Smith', email: 'bob@example.com']
        !graph.containsKey('#Carol')
    }

    def 'should render workflow diagram from #diagram' () {
        given:
        Files.createDirectories(projectDir.resolve('docs')).resolve('diagram.png').text = 'png'

        when:
        final graph = render([] as Set, manifest: [diagram: diagram])

        then:
        graph['main.nf'].image == ['@id': diagram]
        graph[diagram]['@type'] == ['File', 'ImageObject']
        graph['./'].hasPart.contains(['@id': diagram])
        copied == Files.exists(crateDir.resolve('docs/diagram.png'))

        where:
        diagram                              | copied
        'docs/diagram.png'                   | true
        'https://example.com/diagram.png'    | false
    }

    def 'should skip missing workflow diagram' () {
        when:
        final graph = render([] as Set, manifest: [diagram: 'missing.png'])

        then:
        !graph['main.nf'].containsKey('image')
        !graph.containsKey('missing.png')
    }

    @Unroll
    def 'should record command line in run description: #commandLine' () {
        when:
        final graph = render([] as Set, commandLine: commandLine)

        then:
        graph['#00000000-0000-0000-0000-000000000000'].description == expected

        where:
        commandLine                          | expected
        'nextflow run main.nf -profile test' | 'Command-line: nextflow run main.nf -profile test'
        null                                 | null
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
        ['FOO']             | 'FOO'                 | '#module/main.nf/FOO'
        ['FOO']             | 'WF:FOO'              | '#module/main.nf/FOO'
        ['FOO']             | 'WF:FOO_ALIAS'        | '#module/main.nf/FOO'
        ['FOO', 'BAR']      | 'BAR'                 | '#module/main.nf/BAR'
        ['FOO', 'BAR']      | 'OUTER:INNER:BAR'     | '#module/main.nf/BAR'
        ['FOO', 'BAR']      | 'WF:BAR_ALIAS'        | null
    }

    def 'should render module tools from nf-core meta.yml' () {
        given:
        final modulePath = projectDir.resolve('modules/fastqc/main.nf')
        final owner = script(modulePath, ['FASTQC'])
        modulePath.parent.resolve('meta.yml').text = '''\
            name: nf-core/fastqc
            version: 1.0.0
            description: Run FastQC on sequenced reads
            license: MIT
            keywords:
              - quality control
              - qc
            tools:
              - fastqc:
                  description: A quality control tool
            '''.stripIndent()
        final t = task(processor('WF:FASTQC', owner), 'FASTQC (1)')

        when:
        final graph = render([t] as Set)

        then:
        final moduleId = '#module/modules/fastqc/main.nf/FASTQC'
        graph[moduleId].name == 'nf-core/fastqc'
        graph[moduleId].description == 'Run FastQC on sequenced reads'
        graph[moduleId].softwareVersion == '1.0.0'
        graph[moduleId].license == 'MIT'
        graph[moduleId].keywords == ['quality control', 'qc']
        graph[moduleId].hasPart == ['@id': "${moduleId}/fastqc".toString()]
        graph[moduleId].url == modulePath.toUri().toString()
        graph["${moduleId}/fastqc".toString()].description == 'A quality control tool'
    }

    @Unroll
    def 'should record container image #container' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final t = task(processor('FOO', owner), 'FOO (1)', container: container)

        when:
        final graph = render([t] as Set)

        then:
        final id = graph["#task/${t.hash}"].containerImage['@id']
        final image = graph[id]
        image['@type'] == 'ContainerImage'
        image.additionalType == (type ? ['@id': "https://w3id.org/ro/terms/workflow-run#${type}".toString()] : null)
        image.registry == registry
        image.name == name
        image.tag == tag
        image.sha256 == sha256

        where:
        container                                         | type          | registry         | name                     | tag               | sha256
        'ubuntu:22.04'                                    | 'DockerImage' | 'docker.io'      | 'ubuntu'                 | '22.04'           | null
        'quay.io/biocontainers/samtools:1.9--h91753b0_8'  | 'DockerImage' | 'quay.io'        | 'biocontainers/samtools' | '1.9--h91753b0_8' | null
        'localhost:5000/foo/bar'                          | 'DockerImage' | 'localhost:5000' | 'foo/bar'                | null              | null
        'docker://ubuntu@sha256:abc123'                   | 'DockerImage' | 'docker.io'      | 'ubuntu'                 | null              | 'abc123'
        '/images/foo.sif'                                 | 'SIFImage'    | null             | '/images/foo.sif'        | null              | null
        'https://depot.galaxyproject.org/singularity/foo' | null          | null             | null                     | null              | null
    }

    def 'should record conda environment files as build instructions' () {
        given:
        final modulePath = projectDir.resolve('modules/foo/main.nf')
        final owner = script(modulePath, ['FOO'])
        final envFile = modulePath.parent.resolve('environment.yml')
        envFile.text = 'dependencies:\n  - bioconda::samtools=1.9\n'
        final foo = processor('FOO', owner)
        final t1 = task(foo, 'FOO (1)', conda: envFile.toString(), condaEnabled: true)
        final t2 = task(foo, 'FOO (2)', conda: envFile.toString(), condaEnabled: true)
        final t3 = task(processor('BAR', owner), 'BAR (1)', conda: 'bioconda::samtools=1.9', condaEnabled: true)
        final t4 = task(processor('BAZ', owner), 'BAZ (1)', conda: envFile.toString())

        when:
        final graph = render([t1, t2, t3, t4] as Set)

        then:
        graph['#process-step/FOO'].buildInstructions == ['@id': 'modules/foo/environment.yml']
        !graph['#process-step/BAR'].containsKey('buildInstructions')
        !graph['#process-step/BAZ'].containsKey('buildInstructions')
        graph['modules/foo/environment.yml'].encodingFormat == 'application/yaml'
        graph['modules/foo/environment.yml'].conformsTo
        graph['./'].hasPart.contains(['@id': 'modules/foo/environment.yml'])
        crateDir.resolve('modules/foo/environment.yml').text == envFile.text
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
        graph["#task/${t.hash}"].result == ['@id': "#task/${t.hash}/foo.txt"]
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
        graph["#task/${t.hash}"].object == ['@id': '#tmp/collected.txt']
        !graph.containsKey(tmpFile.toUri().toString())
    }

    def 'should copy local input files specified by params into crate' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final input = Files.createDirectories(root.resolve('cases')).resolve('samples.csv')
        input.text = 'id\n1\n'
        final controls = Files.createDirectories(root.resolve('controls')).resolve('samples.csv')
        controls.text = 'id\n2\n'
        final t = task(processor('FOO', owner), 'FOO (1)', inputs: [input])

        when:
        final graph = render([t] as Set, params: [input: input, controls: controls])

        then:
        crateDir.resolve('input/samples.csv').text == 'id\n1\n'
        crateDir.resolve('controls/samples.csv').text == 'id\n2\n'
        graph['input/samples.csv']['@type'] == 'File'
        graph['controls/samples.csv'].description == 'Input file specified by params.controls'
        graph['#param/input'].additionalType == 'File'
        graph['input/samples.csv'].exampleOfWork == ['@id': '#param/input']
        graph['controls/samples.csv'].exampleOfWork == ['@id': '#param/controls']
        !graph.containsKey('#param/input/value')
        !graph.containsKey('#param/controls/value')
        graph["#task/${t.hash}"].object == ['@id': 'input/samples.csv']
    }

    def 'should copy local project files into crate' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final input = Files.createDirectories(projectDir.resolve('scripts')).resolve('meshing.py')
        input.text = 'print(1)'
        final t = task(processor('FOO', owner), 'FOO (1)', inputs: [input])

        when:
        final graph = render([t] as Set)

        then:
        crateDir.resolve('scripts/meshing.py').text == 'print(1)'
        graph['scripts/meshing.py']['@type'] == 'File'
        graph["#task/${t.hash}"].object == ['@id': 'scripts/meshing.py']
    }

    def 'should reference project files by repository url' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final input = Files.createDirectories(projectDir.resolve('scripts')).resolve('meshing.py')
        input.text = 'print(1)'
        final t = task(processor('FOO', owner), 'FOO (1)', inputs: [input])
        final url = 'https://github.com/nextflow-io/hello/tree/abc123/scripts/meshing.py'

        when:
        final graph = render([t] as Set, repository: 'https://github.com/nextflow-io/hello', commitId: 'abc123')

        then:
        !Files.exists(crateDir.resolve('scripts/meshing.py'))
        graph[url]['@type'] == 'File'
        graph["#task/${t.hash}"].object == ['@id': url]
    }

    def 'should represent remote input files specified by params as file entities' () {
        given:
        // a zip filesystem stands in for a remote filesystem
        final zipfs = FileSystems.newFileSystem(root.resolve('remote.zip'), [create: 'true'])
        final input = zipfs.getPath('/samples.csv')
        input.text = 'id\n1\n'
        final controls = zipfs.getPath('/controls.csv')
        controls.text = 'id\n2\n'
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final t = task(processor('FOO', owner), 'FOO (1)', inputs: [input])

        when:
        final graph = render([t] as Set, params: [input: input, controls: controls, reads: zipfs.getPath('/*.fq')])

        then:
        graph[input.toUriString()].exampleOfWork == ['@id': '#param/input']
        graph[controls.toUriString()].exampleOfWork == ['@id': '#param/controls']
        graph[controls.toUriString()].description == 'Input file specified by params.controls'
        !graph.containsKey('#param/input/value')
        !graph.containsKey('#param/controls/value')
        graph['#param/reads/value']['@type'] == 'PropertyValue'
        graph['#00000000-0000-0000-0000-000000000000'].object.containsAll([
            ['@id': input.toUriString()],
            ['@id': controls.toUriString()],
        ])

        cleanup:
        zipfs?.close()
    }

    def 'should represent directories specified by params as dataset entities' () {
        given:
        final refs = Files.createDirectories(root.resolve('refs'))
        final missing = root.resolve('missing')
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final t = task(processor('FOO', owner), 'FOO (1)', inputs: [refs])

        when:
        final graph = render([t] as Set, params: [refs: refs, missing: missing])

        then:
        final id = refs.toUri().toString()
        graph[id]['@type'] == 'Dataset'
        graph[id].exampleOfWork == ['@id': '#param/refs']
        !graph.containsKey('#param/refs/value')
        graph['#param/missing/value']['@type'] == 'PropertyValue'
        graph['#00000000-0000-0000-0000-000000000000'].object.contains(['@id': id])
        graph["#task/${t.hash}"].object == ['@id': id]
    }

    def 'should link directories specified by params to the input files they contain' () {
        given:
        final refs = Files.createDirectories(root.resolve('refs'))
        final genome = refs.resolve('genome.fa')
        genome.text = 'ACGT'
        final index = Files.createDirectories(refs.resolve('index')).resolve('genome.idx')
        index.text = 'idx'
        final other = root.resolve('other.txt')
        other.text = 'other'
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final t = task(processor('FOO', owner), 'FOO (1)', inputs: [genome, index, other])

        when:
        final graph = render([t] as Set, params: [refs: refs])

        then:
        final id = refs.toUri().toString()
        graph[id]['@type'] == 'Dataset'
        graph[id].hasPart == [['@id': genome.toUri().toString()], ['@id': index.toUri().toString()]]
        graph[genome.toUri().toString()]['@type'] == 'File'
    }

    def 'should set step position to the process depth in the workflow DAG' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO', 'BAR', 'BAZ'])
        final (foo, bar, baz) = ['FOO', 'BAR', 'BAZ'].collect { name -> processor(name, owner) }
        final tasks = [task(foo, 'FOO (1)'), task(bar, 'BAR (1)'), task(baz, 'BAZ (1)')] as Set
        final vertices = [foo, bar, baz].collect { p -> Mock(DAG.Vertex) { getProcess() >> p } }
        final edges = [[0, 1], [0, 2]].collect { from, to ->
            Mock(DAG.Edge) {
                getFrom() >> vertices[from]
                getTo() >> vertices[to]
            }
        }
        final dag = Mock(DAG) {
            getVertices() >> vertices
            getEdges() >> edges
        }

        when:
        // FOO -> BAR, FOO -> BAZ
        final graph = render(tasks, dag: dag)

        then:
        graph['#process-step/FOO'].position == 1
        graph['#process-step/BAR'].position == 2
        graph['#process-step/BAZ'].position == 2
    }

    def 'should link published input files to their input entities' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final input = Files.createDirectories(root.resolve('cases')).resolve('samples.csv')
        input.text = 'id\n1\n'
        final data = Files.createFile(root.resolve('data.txt'))
        final t = task(processor('FOO', owner), 'FOO (1)', inputs: [input, data])
        final publishedInput = Files.createFile(crateDir.resolve('samples.csv'))
        final publishedData = Files.createFile(crateDir.resolve('data.txt'))

        when:
        final graph = render([t] as Set, params: [input: input], publishedFiles: [(publishedInput): input, (publishedData): data])

        then:
        graph['#publish/input/samples.csv'].object == ['@id': 'input/samples.csv']
        graph['#publish/input/samples.csv'].result == ['@id': 'samples.csv']
        graph['#publish/input/data.txt'].object == ['@id': data.toUri().toString()]
        graph['#publish/input/data.txt'].result == ['@id': 'data.txt']
    }

    def 'should link input file to every param that specifies it' () {
        given:
        final input = Files.createDirectories(root.resolve('cases')).resolve('samples.csv')
        input.text = 'id\n1\n'

        when:
        final graph = render([] as Set, params: [input: input, samples: input])

        then:
        crateDir.resolve('input/samples.csv').exists()
        !crateDir.resolve('samples/samples.csv').exists()
        graph['input/samples.csv'].exampleOfWork == [['@id': '#param/input'], ['@id': '#param/samples']]
        !graph.containsKey('#param/input/value')
        !graph.containsKey('#param/samples/value')
    }

    def 'should exclude published files outside of crate directory' () {
        given:
        final owner = script(projectDir.resolve('main.nf'), ['FOO'])
        final t = task(processor('FOO', owner), 'FOO (1)', outputs: ['foo.txt'])
        final outside = Files.createFile(root.resolve('foo.txt'))

        when:
        final graph = render([t] as Set, workflowOutputs: [foo: [outside]], publishedFiles: [(outside): t.workDir.resolve('foo.txt')])

        then:
        !graph['./'].hasPart.contains(['@id': '../foo.txt'])
        !graph['#output/foo/value'].containsKey('value')
        graph['#00000000-0000-0000-0000-000000000000'].result == ['@id': '#output/foo/value']
        !graph['#00000000-0000-0000-0000-000000000000'].object.contains(['@id': '#output/foo/value'])
        !graph.values().any { entity -> entity.name == 'publish' && entity.result == ['@id': '../foo.txt'] }
    }

    def 'should publish a task output to multiple targets' () {
        given:
        final t = task(processor('FOO', script(projectDir.resolve('main.nf'), ['FOO'])), 'FOO (1)', outputs: ['foo.txt'])
        final source = t.workDir.resolve('foo.txt')
        final a = Files.createFile(crateDir.resolve('a.txt'))
        final b = Files.createFile(crateDir.resolve('b.txt'))

        when:
        final graph = render([t] as Set, publishedFiles: [(a): source, (b): source])

        then:
        graph["#publish/${t.hash}/foo.txt"].result as Set == [['@id': 'a.txt'], ['@id': 'b.txt']] as Set
        graph['a.txt']
        graph['b.txt']
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
        graph['https://ror.org/000000000'].url == 'https://ror.org/000000000'
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
    def 'should parse parameter schema #schema' () {
        given:
        final schemaPath = root.resolve('schema.json')
        schemaPath.text = schema

        expect:
        WrrocRenderer.getParameterSchema(schemaPath) == [foo: [type: 'string'], bar: [type: 'integer']]

        where:
        schema << [
            '{ "properties": { "foo": { "type": "string" }, "bar": { "type": "integer" } } }',
            '{ "properties": { "foo": { "type": "string" } }, "$defs": { "g": { "properties": { "bar": { "type": "integer" } } } } }',
            '{ "defs": { "g1": { "properties": { "foo": { "type": "string" } } }, "g2": { "properties": { "bar": { "type": "integer" } } } } }',
            '{ "definitions": { "g1": { "properties": { "foo": { "type": "string" }, "bar": { "type": "integer" } } }, "g2": { "properties": { "bar": { "type": "integer" } } } } }',
        ]
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
