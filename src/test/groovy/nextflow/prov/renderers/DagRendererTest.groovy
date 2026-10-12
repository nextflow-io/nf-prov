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

import com.google.common.hash.HashCode
import nextflow.Session
import nextflow.file.FileHelper
import nextflow.file.FileHolder
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import nextflow.prov.ProvDagConfig
import nextflow.script.WorkflowMetadata
import nextflow.script.params.FileOutParam
import spock.lang.Specification
import spock.lang.TempDir

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class DagRendererTest extends Specification {

    @TempDir
    Path root

    Path workDir
    Path dagFile

    int taskCount = 0

    def setup() {
        workDir = Files.createDirectories(root.resolve('work'))
        dagFile = root.resolve('dag.html')
    }

    // -- fixtures

    TaskRun task(Map opts = [:], String processName, String name) {
        final hash = HashCode.fromLong(++taskCount)
        final taskDir = workDir.resolve(hash.toString())
        final inputs = (opts.inputs ?: []) as List<Path>
        final outputs = ((opts.outputs ?: []) as List<String>).collect { fileName -> taskDir.resolve(fileName) }
        final processor = Mock(TaskProcessor) {
            getName() >> processName
        }
        Mock(TaskRun) {
            getProcessor() >> processor
            getName() >> name
            getHash() >> hash
            getInputFiles() >> inputs.collect { source -> new FileHolder(source) }
            getOutputsByType(FileOutParam) >> [(Mock(FileOutParam)): outputs]
        }
    }

    Session session() {
        final metadata = Mock(WorkflowMetadata) {
            getProjectDir() >> root.resolve('project')
            getWorkDir() >> workDir
        }
        Mock(Session) {
            getWorkflowMetadata() >> metadata
        }
    }

    /**
     * Render the task graph and return the Mermaid diagram.
     */
    String render(Set<TaskRun> tasks) {
        new DagRenderer(new ProvDagConfig([file: dagFile.toString()])).render(session(), tasks, [:], [:])
        final html = dagFile.text
        return html.substring(html.indexOf('flowchart TD'), html.indexOf('    end\n', html.lastIndexOf('subgraph " "')) + 7)
    }

    // -- tests

    def 'should render task graph' () {
        given:
        final input = root.resolve('reads (1).fq')
        final t1 = task('WF:FOO', 'WF:FOO (sample "1")', inputs: [input], outputs: ['foo|1.txt'])
        final fooOut = workDir.resolve("${t1.hash}/foo|1.txt")
        final t2 = task('BAR', 'BAR (1)', inputs: [fooOut], outputs: ['bar[1].txt'])

        when:
        final mmd = render([t1, t2] as Set)

        then:
        mmd == '''\
            flowchart TD
                subgraph " "
                in0["reads (1).fq"]
                end
                subgraph s0 ["WF"]
                t0(["FOO (sample #quot;1#quot;)"])
                end
                t1(["BAR (1)"])
                in0 --> t0
                t0 -->|"foo|1.txt"| t1
                t1 --> out0
                subgraph " "
                out0["bar[1].txt"]
                end'''.stripIndent()
    }

    def 'should render nested subworkflows' () {
        given:
        final t1 = task('OUTER:A:STATS:FOO', 'OUTER:A:STATS:FOO (1)')
        final t2 = task('OUTER:B:STATS:FOO', 'OUTER:B:STATS:FOO (1)')
        final t3 = task('OUTER:BAR', 'OUTER:BAR')

        when:
        final mmd = render([t1, t2, t3] as Set)

        then:
        mmd.contains('''\
                subgraph s0 ["OUTER"]
                subgraph s1 ["A"]
                subgraph s2 ["STATS"]
                t0(["FOO (1)"])
                end
                end
                subgraph s3 ["B"]
                subgraph s4 ["STATS"]
                t1(["FOO (1)"])
                end
                end
                t2(["BAR"])
                end
            '''.stripIndent())
    }

    def 'should render shared workflow input once' () {
        given:
        final input = root.resolve('input.txt')
        final t1 = task('FOO', 'FOO (1)', inputs: [input])
        final t2 = task('BAR', 'BAR (1)', inputs: [input])

        when:
        final mmd = render([t1, t2] as Set)

        then:
        mmd.count('["input.txt"]') == 1
        mmd.contains('in0 --> t0')
        mmd.contains('in0 --> t1')
    }

    def 'should link remote workflow inputs' () {
        given:
        final input = FileHelper.asPath('https://example.com/data/input.txt')
        final t = task('FOO', 'FOO (1)', inputs: [input])

        when:
        final mmd = render([t] as Set)

        then:
        mmd.contains('click in0 href "https://example.com/data/input.txt" _blank')
    }

}
