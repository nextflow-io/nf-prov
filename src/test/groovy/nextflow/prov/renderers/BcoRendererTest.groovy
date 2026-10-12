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
import nextflow.SysEnv
import nextflow.config.Manifest
import nextflow.exception.AbortOperationException
import nextflow.file.FileHolder
import nextflow.processor.TaskId
import nextflow.processor.TaskRun
import nextflow.prov.ProvBcoConfig
import nextflow.script.WorkflowMetadata
import nextflow.script.params.FileOutParam
import spock.lang.Specification
import spock.lang.TempDir

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class BcoRendererTest extends Specification {

    @TempDir
    Path root

    Path projectDir
    Path workDir
    Path bcoFile

    int taskCount = 0

    def setup() {
        projectDir = Files.createDirectories(root.resolve('project'))
        workDir = Files.createDirectories(root.resolve('work'))
        bcoFile = root.resolve('bco.json')
    }

    // -- fixtures

    TaskRun task(Map opts = [:], String name) {
        final id = ++taskCount
        final hash = HashCode.fromLong(id)
        final taskDir = Files.createDirectories(workDir.resolve(hash.toString()))
        final inputs = (opts.inputs ?: []) as List<Path>
        final outputs = ((opts.outputs ?: []) as List<String>).collect { fileName -> taskDir.resolve(fileName) }
        Mock(TaskRun) {
            getId() >> TaskId.of(id)
            getName() >> name
            getHash() >> hash
            getInputFiles() >> inputs.collect { source -> new FileHolder(source) }
            getOutputsByType(FileOutParam) >> [(Mock(FileOutParam)): outputs]
        }
    }

    Session session(Map opts = [:]) {
        final metadata = Mock(WorkflowMetadata) {
            getProjectDir() >> projectDir
            getWorkDir() >> workDir
            getScriptFile() >> projectDir.resolve('main.nf')
            getManifest() >> new Manifest((opts.manifest ?: [:]) as Map)
            getStart() >> OffsetDateTime.parse('2026-01-01T00:00:00Z')
            getNextflow() >> NextflowMeta.instance
            getRepository() >> opts.repository
            getCommitId() >> opts.commitId
        }
        Mock(Session) {
            getConfig() >> [params: opts.params ?: [:], prov: [formats: [bco: opts.bco ?: [:]]]]
            getWorkflowMetadata() >> metadata
        }
    }

    Map render(Map opts = [:], Set<TaskRun> tasks) {
        final renderer = new BcoRenderer(new ProvBcoConfig([file: bcoFile.toString(), overwrite: true]))
        renderer.render(session(opts), tasks, [:], (opts.publishedFiles ?: [:]) as Map<Path,Path>)
        return new JsonSlurper().parse(bcoFile.toFile()) as Map
    }

    // -- tests

    def 'should render a BCO manifest' () {
        given:
        final input = root.resolve('input.txt')
        final refs = ['genome.fa', 'genome.fa.fai', 'genes.gtf'].collect { name -> root.resolve(name) }
        final t1 = task('FOO (1)', inputs: [input, *refs], outputs: ['foo.txt'])
        final fooOut = workDir.resolve("${t1.hash}/foo.txt")
        final t2 = task('BAR (1)', inputs: [fooOut], outputs: ['bar.json'])
        final barOut = workDir.resolve("${t2.hash}/bar.json")
        barOut.text = '{}'
        final published = root.resolve('results/bar.json')

        when:
        final bco = render(
            [t2, t1] as Set,
            manifest: [name: 'test', version: '1.0', license: 'MIT', contributors: [[name: 'Alice', contribution: ['author', 'maintainer']]]],
            params: [input: input.toString(), opts: [n: 1]],
            publishedFiles: [(barOut): published])

        then:
        bco.object_id.startsWith('urn:uuid:')
        bco.spec_version == 'https://w3id.org/ieee/ieee-2791-schema/2791object.json'
        bco.etag

        and:
        bco.provenance_domain.name == 'test'
        bco.provenance_domain.version == '1.0'
        bco.provenance_domain.license == 'MIT'
        bco.provenance_domain.created == '2026-01-01T00:00:00Z'
        bco.provenance_domain.contributors*.name == ['Alice']
        bco.provenance_domain.contributors[0].contribution == ['authoredBy', 'curatedBy']
        !bco.provenance_domain.containsKey('derived_from')
        !bco.provenance_domain.contributors[0].containsKey('email')

        and:
        bco.description_domain.pipeline_steps*.step_number == [1, 2]
        bco.description_domain.pipeline_steps*.description == ['FOO (1)', 'BAR (1)']
        bco.description_domain.pipeline_steps[0].input_list == [input, *refs].collect { source -> [uri: "file://${source}"] }
        bco.description_domain.pipeline_steps[0].output_list == [[uri: "work/${t1.hash}/foo.txt"]]
        bco.description_domain.pipeline_steps[1].input_list == [[uri: "work/${t1.hash}/foo.txt"]]

        and:
        bco.execution_domain.script == ["file://${projectDir}/main.nf"]
        bco.execution_domain.software_prerequisites[0].version == NextflowMeta.instance.version.toString()
        bco.parametric_domain as Set == [
            [param: 'input', value: "file://${input}", step: '0'],
            [param: 'opts.n', value: '1', step: '0']
        ] as Set

        and:
        bco.io_domain.input_subdomain as Set == [input, *refs].collect { source -> [uri: [uri: "file://${source}"]] } as Set
        bco.io_domain.output_subdomain == [[mediatype: 'application/json', uri: [filename: "work/${t2.hash}/bar.json", uri: "file://${published}"]]]
        bco.extension_domain == []
    }

    def 'should render BCO config options' () {
        given:
        SysEnv.push([FOO: 'foo'])
        final bcoOpts = [
            provenance_domain: [review: [[status: 'approved']], derived_from: 'x', obsolete_after: '2030-01-01', embargo: [start_time: 'a', end_time: 'b']],
            usability_domain: ['useful'],
            description_domain: [keywords: ['kw'], xref: [[namespace: 'ns']]],
            execution_domain: [external_data_endpoints: [[name: 'db', url: 'https://example.com']], environment_variables: ['FOO', 'MISSING']]
        ]

        when:
        final bco = render([] as Set, bco: bcoOpts)

        then:
        bco.provenance_domain.review == [[status: 'approved']]
        bco.provenance_domain.derived_from == 'x'
        bco.provenance_domain.obsolete_after == '2030-01-01'
        bco.provenance_domain.embargo == [start_time: 'a', end_time: 'b']
        bco.usability_domain == ['useful']
        bco.description_domain.keywords == ['kw']
        bco.description_domain.xref == [[namespace: 'ns']]
        bco.execution_domain.external_data_endpoints == [[name: 'db', url: 'https://example.com']]
        bco.execution_domain.environment_variables == [FOO: 'foo']

        cleanup:
        SysEnv.pop()
    }

    def 'should render git repository extension' () {
        when:
        final bco = render([] as Set, repository: 'https://github.com/nextflow-io/hello', commitId: 'abc123')

        then:
        bco.extension_domain.size() == 1
        bco.extension_domain[0].scm_extension.scm_repository == 'https://github.com/nextflow-io/hello'
        bco.extension_domain[0].scm_extension.scm_commit == 'abc123'
        bco.extension_domain[0].scm_extension.scm_path == 'main.nf'
        bco.extension_domain[0].scm_extension.scm_preview == 'https://github.com/nextflow-io/hello/tree/abc123/main.nf'
    }

    def 'should compute stable etag' () {
        expect:
        render([] as Set).etag == render([] as Set).etag
        render([] as Set).etag != render([] as Set, manifest: [name: 'other']).etag
    }

    def 'should not overwrite existing file by default' () {
        given:
        bcoFile.text = '{}'

        when:
        new BcoRenderer(new ProvBcoConfig([file: bcoFile.toString()]))

        then:
        thrown(AbortOperationException)

        when:
        new BcoRenderer(new ProvBcoConfig([file: bcoFile.toString(), overwrite: true]))

        then:
        noExceptionThrown()
        !Files.exists(bcoFile)
    }

}
