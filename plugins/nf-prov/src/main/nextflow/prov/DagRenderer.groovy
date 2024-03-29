/*
 * Copyright 2023, Seqera Labs
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

package nextflow.prov

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import nextflow.Session
import nextflow.processor.TaskRun
import nextflow.script.WorkflowMetadata
import nextflow.util.StringUtils

/**
 * Renderer for the task graph format.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class DagRenderer implements Renderer {

    private Path path

    private boolean overwrite

    @Delegate
    private PathNormalizer normalizer

    DagRenderer(Map opts) {
        path = opts.file as Path
        overwrite = opts.overwrite as Boolean

        ProvHelper.checkFileOverwrite(path, overwrite)
    }

    @Override
    void render(Session session, Set<TaskRun> tasks, Map<Path,Path> workflowOutputs) {
        // get workflow metadata
        final metadata = session.workflowMetadata
        this.normalizer = new PathNormalizer(metadata)

        // get workflow inputs
        final taskLookup = ProvHelper.getTaskLookup(tasks)
        final workflowInputs = ProvHelper.getWorkflowInputs(tasks, taskLookup)

        // construct task graph
        final dag = new Dag(getVertices(tasks), taskLookup)

        path.text = renderHtml(dag)
    }

    private Map<TaskRun,Vertex> getVertices(Set<TaskRun> tasks) {
        def result = [:]
        for( def task : tasks ) {
            final inputs = task.getInputFilesMap()
            final outputs = ProvHelper.getTaskOutputs(task)

            result[task] = new Vertex(result.size(), task.name, inputs, outputs)
        }

        return result
    }

    /**
     * Render the task graph as a Mermaid diagram embedded
     * in an HTML document.
     *
     * @param dag
     */
    private String renderHtml(Dag dag) {
        // load html template
        final writer = new StringWriter()
        final res = DagRenderer.class.getResourceAsStream('mermaid.dag.template.html')
        int ch
        while( (ch=res.read()) != -1 ) {
            writer.append(ch as char)
        }
        final template = writer.toString()

        // render html document
        final mmd = renderDiagram(dag)
        return template.replace('REPLACE_WITH_NETWORK_DATA', mmd)
    }

    /**
     * Render the task graph as a Mermaid diagram.
     *
     * @param dag
     */
    private String renderDiagram(Dag dag) {
        // construct task tree
        final taskTree = getTaskTree(dag.vertices)

        // render diagram
        List<String> lines = []
        lines << "flowchart TD"

        // render workflow inputs
        Map<Path,String> inputs = [:]

        lines << "    subgraph \" \""

        dag.vertices.each { task, vertex ->
            vertex.inputs.each { name, path ->
                if( dag.getProducerVertex(path) || path in inputs )
                    return

                inputs[path] = "in${inputs.size()}".toString()
                lines << "    ${inputs[path]}[\"${path.name}\"]".toString()

                // add hyperlink if path is remote URL
                final sourcePath = normalizePath(path)
                if( isRemotePath(sourcePath) )
                    lines << "    click ${inputs[path]} href \"${sourcePath}\" _blank".toString()
            }
        }

        lines << "    end"

        // render tasks
        renderTaskTree(lines, null, taskTree)

        // render task inputs
        final taskOutputs = [] as Set<Path>

        dag.vertices.each { task, vertex ->
            vertex.inputs.each { name, path ->
                // render task input from predecessor task
                final pred = dag.getProducerVertex(path)
                if( pred != null ) {
                    lines << "    ${pred.name} -->|${path.name}| ${vertex.name}".toString()
                    taskOutputs << path
                }

                // render task input from workflow input
                else {
                    lines << "    ${inputs[path]} --> ${vertex.name}".toString()
                }
            }
        }

        // render task outputs
        final outputs = [:] as Map<Path,String>

        dag.vertices.each { task, vertex ->
            vertex.outputs.each { path ->
                if( path in taskOutputs || path in outputs )
                    return

                outputs[path] = "out${outputs.size()}".toString()
                lines << "    ${vertex.name} --> ${outputs[path]}".toString()
            }
        }

        // render workflow outputs
        lines << "    subgraph \" \""

        outputs.each { path, label ->
            lines << "    ${label}[${path.name}]".toString()
        }

        lines << "    end"

        return lines.join('\n')
    }

    /**
     * Construct a task tree with a subgraph for each subworkflow.
     *
     * @param vertices
     */
    private Map getTaskTree(Map<TaskRun,Vertex> vertices) {
        def taskTree = [:]

        for( def entry : vertices ) {
            def task = entry.key
            def vertex = entry.value

            // infer subgraph keys from fully qualified process name
            final result = getSubgraphKeys(task.processor.name)
            final keys = (List)result[0]

            // update vertex label
            final hash = task.hash.toString()
            vertex.label = task.name.replace(task.processor.name, (String)result[1])

            // navigate to given subgraph
            def subgraph = taskTree
            for( def key : keys ) {
                if( key !in subgraph )
                    subgraph[key] = [:]
                subgraph = subgraph[key]
            }

            // add vertex to tree
            subgraph[vertex.name] = vertex
        }

        return taskTree
    }

    /**
     * Get the subgraph keys from a fully qualified process name.
     *
     * @param name
     */
    private List getSubgraphKeys(String name) {
        final tokens = name.tokenize(':')
        return [
            tokens[0..<tokens.size()-1],
            tokens.last()
        ]
    }

    private boolean isRemotePath(String path) {
        if( !path ) return false
        final result = StringUtils.getUrlProtocol(path)
        return result != null && result != 'file'
    }

    /**
     * Render a tree of tasks and subgraphs.
     *
     * @param lines
     * @param name
     * @param taskTree
     */
    private void renderTaskTree(List<String> lines, String name, Map<String,Object> taskTree) {
        if( name )
            lines << "    subgraph ${name}".toString()

        taskTree.each { key, value ->
            if( value instanceof Map )
                renderTaskTree(lines, key, value)
            else if( value instanceof Vertex )
                lines << "    ${renderTask(value)}".toString()
        }

        if( name )
            lines << "    end"
    }

    private String renderTask(Vertex vertex) {
        return "${vertex.name}([\"${vertex.label}\"])"
    }

    @TupleConstructor
    static class Dag {
        Map<TaskRun,Vertex> vertices
        Map<Path,TaskRun> taskLookup

        Vertex getProducerVertex(Path path) {
            vertices[taskLookup[path]]
        }
    }

    @TupleConstructor
    static class Vertex {
        int index
        String label
        Map<String,Path> inputs
        List<Path> outputs

        String getName() { "t${index}" }

        @Override
        String toString() { label }
    }

}
