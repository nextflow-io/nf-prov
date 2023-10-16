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
import nextflow.exception.AbortOperationException
import nextflow.file.FileHelper
import nextflow.processor.TaskRun
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
     * Get the list of output files for a task.
     *
     * @param task
     */
    static List<Path> getTaskOutputs(TaskRun task) {
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
        final result = [:] as Map<Path,TaskRun>

        for( def task : tasks )
            for( def output : getTaskOutputs(task) )
                result[output] = task

        return result
    }

    /**
     * Get the list of workflow inputs. A workflow input is an input file
     * to a task that was not produced by another task.
     *
     * @param tasks
     * @param taskLookup
     */
    static Set<Path> getWorkflowInputs(Set<TaskRun> tasks, Map<Path,TaskRun> taskLookup) {
        final result = [] as Set<Path>

        tasks.each { task ->
            task.getInputFilesMap().each { name, path ->
                if( taskLookup[path] )
                    return

                result << path
            }
        }

        return result
    }

}
