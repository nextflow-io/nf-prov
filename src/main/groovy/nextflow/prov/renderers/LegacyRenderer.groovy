/*
 * Copyright 2022, Seqera Labs
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

import java.nio.file.Path

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.file.FileHolder
import nextflow.processor.TaskRun
import nextflow.prov.Renderer
import nextflow.prov.util.ProvHelper

/**
 * Renderer for the legacy manifest format.
 *
 * @author Bruno Grande <bruno.grande@sagebase.org>
 */
@CompileStatic
class LegacyRenderer implements Renderer {

    private Path path

    private boolean overwrite

    LegacyRenderer(Map opts) {
        path = (opts.file as Path).complete()
        overwrite = opts.overwrite as Boolean

        ProvHelper.checkFileOverwrite(path, overwrite)
    }

    private static def jsonify(root) {
        if ( root instanceof Map )
            root.collectEntries( (k, v) -> [k, jsonify(v)] )

        else if ( root instanceof Collection )
            root.collect( v -> jsonify(v) )

        else if ( root instanceof FileHolder )
            jsonify(root.storePath)

        else if ( root instanceof Path )
            root.toUriString()

        else if ( root instanceof Boolean || root instanceof Number )
            root

        else
            root.toString()
    }

    Map renderTask(TaskRun task) {
        // TODO: Figure out what the '$' input/output means
        //       Omitting them from manifest for now
        return [
            'id': task.id as String,
            'name': task.name,
            'cached': task.cached,
            'process': task.processor.name,
            'script': task.script,
            'inputs': task.inputs.findResults { inParam, object -> 
                def inputMap = [ 
                    'name': inParam.getName(),
                    'value': jsonify(object) 
                ] 
                inputMap['name'] != '$' ? inputMap : null
            },
            'outputs': task.outputs.findResults { outParam, object -> 
                def outputMap = [
                    'name': outParam.getName(),
                    'emit': outParam.getChannelEmitName(),
                    'value': jsonify(object) 
                ] 
                outputMap['name'] != '$' ? outputMap : null
            }
        ]
    }

    @Override
    void render(Session session, Set<TaskRun> tasks, Map<Path,Path> outputs) {
        // generate task manifest
        def tasksMap = tasks.inject([:]) { accum, task ->
            accum[task.id] = renderTask(task)
            accum
        }

        // generate temporary output-task map
        def taskLookup = tasksMap.inject([:]) { accum, id, task ->
            task['outputs'].each { output ->
                // Make sure to handle tuples of outputs
                def values = output['value']
                if ( values instanceof Collection )
                    values.each { accum.put(it, task['id']) }
                else
                    accum.put(values, task['id'])
            }
            accum
        }

        // render JSON output
        def manifest = [
            'pipeline': session.config.manifest,
            'published': outputs.collect { source, target -> [
                'source': source.toUriString(),
                'target': target.toUriString(),
                'publishingTaskId': taskLookup[source.toUriString()],
            ] },
            'tasks': tasksMap
        ]

        path.text = JsonOutput.prettyPrint(JsonOutput.toJson(manifest))
    }

}
