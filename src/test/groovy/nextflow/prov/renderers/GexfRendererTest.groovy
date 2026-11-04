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
import groovy.xml.XmlSlurper
import nextflow.Session
import nextflow.file.FileHolder
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import nextflow.prov.ProvGexfConfig
import nextflow.script.params.FileOutParam
import spock.lang.Specification
import spock.lang.TempDir

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class GexfRendererTest extends Specification {

    @TempDir
    Path root

    Path workDir
    Path gexfFile

    int taskCount = 0

    def setup() {
        workDir = Files.createDirectories(root.resolve('work'))
        gexfFile = root.resolve('graph.gexf')
    }

    // -- fixtures

    TaskRun task(Map opts = [:], String name) {
        final hash = HashCode.fromLong(++taskCount)
        final taskDir = Files.createDirectories(workDir.resolve(hash.toString()))
        final inputs = (opts.inputs ?: []) as List<Path>
        final outputs = ((opts.outputs ?: [:]) as Map<String,String>).collect { fileName, content ->
            final target = taskDir.resolve(fileName)
            target.text = content
            target
        }
        final processor = Mock(TaskProcessor) {
            getName() >> name.tokenize(' ')[0]
        }
        Mock(TaskRun) {
            getProcessor() >> processor
            getName() >> name
            getHash() >> hash
            isCached() >> (opts.cached ?: false)
            isAborted() >> false
            getInputFiles() >> inputs.collect { source -> new FileHolder(source) }
            getOutputsByType(FileOutParam) >> [(Mock(FileOutParam)): outputs]
        }
    }

    /**
     * Render the graph and return the nodes by id and edges
     * as [source id, target id] pairs.
     */
    Map render(Set<TaskRun> tasks) {
        new GexfRenderer(new ProvGexfConfig([file: gexfFile.toString()])).render(Mock(Session), tasks, [:], [:])
        final gexf = new XmlSlurper().parse(gexfFile.toFile())
        final nodes = gexf.graph.nodes.node.collectEntries { node -> [node.@id.text(), node] }
        final edges = gexf.graph.edges.edge.collect { edge ->
            final source = edge.@source.text()
            final target = edge.@target.text()
            assert source in nodes && target in nodes : "dangling edge ${edge.@id}"
            [source, target]
        }
        return [nodes: nodes, edges: edges as Set]
    }

    static node(Map graph, String label) {
        final matches = graph.nodes.values().findAll { node -> node.@label.text() == label }
        assert matches.size() == 1 : "expected one node with label '${label}'"
        return matches.first()
    }

    static Set<List<String>> edgeLabels(Map graph) {
        graph.edges.collect { source, target -> [graph.nodes[source].@label.text(), graph.nodes[target].@label.text()] } as Set
    }

    static Map<String,String> attvalues(node) {
        node.attvalues.attvalue.collectEntries { av -> [av.@for.text(), av.@value.text()] }
    }

    // -- tests

    def 'should render task graph' () {
        given:
        final input = root.resolve('input.fastq.gz')
        input.text = 'reads'
        final t1 = task('FOO (1)', inputs: [input], outputs: ['foo.txt': 'a'], cached: true)
        final fooOut = workDir.resolve("${t1.hash}/foo.txt")
        // tasks may share a name (e.g. same tag)
        final t2 = task('BAR (1)', inputs: [fooOut], outputs: ['bar1.txt': 'b'])
        final t3 = task('BAR (1)', inputs: [fooOut], outputs: ['bar2.txt': 'b'])

        when:
        final graph = render([t1, t2, t3] as Set)

        then:
        graph.nodes.values()*.@label*.text().sort() == ['BAR (1)', 'BAR (1)', 'FOO (1)', 'bar1.txt', 'bar2.txt', 'foo.txt', 'input.fastq.gz']
        graph.edges.size() == 6
        edgeLabels(graph) == [
            ['input.fastq.gz', 'FOO (1)'],
            ['FOO (1)', 'foo.txt'],
            ['foo.txt', 'BAR (1)'],
            ['BAR (1)', 'bar1.txt'],
            ['BAR (1)', 'bar2.txt']
        ] as Set

        and:
        attvalues(node(graph, 'FOO (1)')) == ['0': 'true', '1': 'false', '2': 'task', '4': 'FOO']
        attvalues(node(graph, 'input.fastq.gz'))['2'] == 'file'
        attvalues(node(graph, 'input.fastq.gz'))['3'] == input.toString()
        attvalues(node(graph, 'input.fastq.gz'))['5'] == '5'
        attvalues(node(graph, 'input.fastq.gz'))['6'] == 'fastq'
        attvalues(node(graph, 'input.fastq.gz'))['8']
    }

    def 'should scale file nodes by size' () {
        given:
        final t = task('FOO (1)', inputs: [root.resolve('missing.txt')], outputs: ['small.txt': 'a', 'large.txt': 'a' * 11])

        when:
        final graph = render([t] as Set)

        then:
        node(graph, 'small.txt').size.@value.text() == '10.0'
        node(graph, 'large.txt').size.@value.text() == '100.0'
        node(graph, 'missing.txt').size.isEmpty()
    }

    def 'should omit attributes for missing files' () {
        given:
        final t = task('FOO (1)', inputs: [root.resolve('missing')])

        when:
        final attrs = attvalues(node(render([t] as Set), 'missing'))

        then:
        attrs.keySet() == ['2', '3'] as Set
    }

}
