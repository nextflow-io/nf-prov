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

package nextflow.prov.util

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.exception.AbortOperationException
import nextflow.dag.DAG
import nextflow.file.FileHelper
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import nextflow.script.ScriptType
import nextflow.script.params.FileOutParam

/**
 * Helper methods for provenance reports.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class ProvHelper {

    /**
     * Check whether a file already exists and throw an
     * error if it cannot be overwritten.
     *
     * @param path
     * @param overwrite
     */
    static void checkFileOverwrite(Path path, boolean overwrite) {
        final attrs = FileHelper.readAttributes(path)
        if( attrs ) {
            if( overwrite && (attrs.isDirectory() || !path.delete()) )
                throw new AbortOperationException("Unable to overwrite existing provenance file: ${path.toUriString()}")
            else if( !overwrite )
                throw new AbortOperationException("Provenance file already exists: ${path.toUriString()}")
        }
    }

    /**
     * Get the encodingFormat of a file as MIME Type.
     *
     * @param path Path to file
     */
    static String getEncodingFormat(Path path) {
        if( !path || !path.isFile() )
            return null

        final mime = Files.probeContentType(path)
        if( mime )
            return mime

        // It seems that YAML has a media type only since beginning of 2024
        // Set this by hand if this is run on older systems:
        // https://httptoolkit.com/blog/yaml-media-type-rfc/
        if( ["yml", "yaml"].contains(path.getExtension()) )
            return "application/yaml"

        return null
    }

    /**
     * Get the remote file staging directory for a workflow run.
     *
     * @param session
     */
    static Path getStageDir(Session session) {
        return session.workDir.resolve("stage-${session.uniqueId}")
    }

    /**
     * Determine whether a task completed successfully. Native (exec)
     * tasks have no exit status, so they succeed if there is no error.
     *
     * @param task
     */
    static boolean isSuccess(TaskRun task) {
        return task.type == ScriptType.GROOVY
            ? task.error == null
            : task.isSuccess()
    }

    /**
     * Get the input files for a task as a mapping from stage name to source path.
     *
     * @param task
     */
    static Map<String,Path> getTaskInputs(TaskRun task) {
        return task.getInputFiles().collectEntries { holder ->
            [holder.getStageName(), holder.getSourcePath()]
        } as Map<String,Path>
    }

    /**
     * Get the list of output files for a task.
     *
     * @param task
     */
    static List<Path> getTaskOutputs(TaskRun task) {
        if( task.hasTypedInputsOutputs() )
            return task.outputFiles.toList()
        return task
            .getOutputsByType(FileOutParam)
            .values()
            .flatten() as List<Path>
    }

    /**
     * Get a mapping of output file to the task that produced it.
     *
     * @param tasks
     */
    static Map<Path,TaskRun> getTaskLookup(Set<TaskRun> tasks) {
        final Map<Path,TaskRun> result = [:]
        for( def task : tasks ) {
            for( def output : getTaskOutputs(task) )
                result[output] = task
        }
        return result
    }

    /**
     * Find the task output that contains a given path, i.e. the path
     * itself or a parent directory that was produced by a task.
     *
     * @param path
     * @param taskLookup
     */
    static Path findTaskOutput(Path path, Map<Path,TaskRun> taskLookup) {
        for( def p = path; p != null; p = p.getParent() ) {
            if( taskLookup.containsKey(p) )
                return p
        }
        return null
    }

    /**
     * Get the list of workflow inputs. A workflow input is an input file
     * to a task that was not produced by another task.
     *
     * @param tasks
     * @param taskLookup
     */
    static Set<Path> getWorkflowInputs(Set<TaskRun> tasks, Map<Path,TaskRun> taskLookup) {
        return tasks.stream()
            .flatMap(task -> getTaskInputs(task).values().stream())
            .filter(path -> !findTaskOutput(path, taskLookup))
            .collect(Collectors.toSet())
    }

    /**
     * Determine whether a task input file was created in the work/tmp/
     * directory (i.e. by a collectFile operator).
     *
     * @param source
     * @param session
     */
    static boolean isTmpInput(Path source, Session session) {
        final tmpDir = session.workDir.resolve('tmp')
        return source.startsWith(tmpDir)
    }

    /**
     * Get the step number of each process, defined as the number of
     * processes on the longest path to the process in the workflow DAG.
     * Parallel processes have the same step number.
     *
     * @param dag
     */
    static Map<TaskProcessor,Integer> getProcessSteps(DAG dag) {
        final Map<DAG.Vertex,List<DAG.Vertex>> preds = [:]
        for( final edge : dag.edges ) {
            if( edge.from != null && edge.to != null )
                preds.computeIfAbsent(edge.to, (k) -> new ArrayList<DAG.Vertex>()).add(edge.from)
        }

        final Map<DAG.Vertex,Integer> depths = [:]
        final Map<TaskProcessor,Integer> result = [:]
        for( final vertex : dag.vertices ) {
            if( vertex.process != null )
                result[vertex.process] = getDepth(vertex, preds, depths)
        }
        return result
    }

    private static int getDepth(DAG.Vertex vertex, Map<DAG.Vertex,List<DAG.Vertex>> preds, Map<DAG.Vertex,Integer> depths) {
        if( depths.containsKey(vertex) )
            return depths[vertex]
        // placeholder to break cycles (e.g. process recursion)
        depths[vertex] = 0
        int depth = 0
        for( final pred : preds.getOrDefault(vertex, []) )
            depth = Math.max(depth, getDepth(pred, preds, depths))
        if( vertex.process != null )
            depth += 1
        depths[vertex] = depth
        return depth
    }

    /**
     * Get the params whose values are paths, as a mapping
     * from path to param names. Paths nested in lists and
     * maps (e.g. records) are attributed to the enclosing param.
     *
     * @param params
     */
    static Map<Path,List<String>> getParamPaths(Map<String,?> params) {
        final Map<Path,List<String>> result = [:]
        for( final entry : params.entrySet() )
            addParamPaths(entry.key, entry.value, result)
        return result
    }

    private static void addParamPaths(String name, Object value, Map<Path,List<String>> result) {
        if( value instanceof Map ) {
            for( final v : value.values() )
                addParamPaths(name, v, result)
            return
        }
        if( value instanceof Collection ) {
            for( final v : value )
                addParamPaths(name, v, result)
            return
        }
        final path = asParamPath(value)
        if( path != null && !result.getOrDefault(path, []).contains(name) )
            result.computeIfAbsent(path, (k) -> new ArrayList<String>()).add(name)
    }

    private static Path asParamPath(Object value) {
        if( value instanceof Path )
            return value.complete().normalize()
        // skip blank values, which would resolve to the launch directory
        if( value !instanceof CharSequence || !value.toString().trim() )
            return null
        try {
            return (value.toString() as Path).complete().normalize()
        }
        catch( Exception e ) {
            return null
        }
    }

    /**
     * Find the params whose path is the given path or a
     * parent directory of it.
     *
     * @param source
     * @param paramPaths
     */
    static List<String> findParams(Path source, Map<Path,List<String>> paramPaths) {
        final List<String> result = []
        for( def p = source.normalize(); p != null; p = p.getParent() )
            result.addAll(paramPaths.getOrDefault(p, []))
        return result
    }

}
