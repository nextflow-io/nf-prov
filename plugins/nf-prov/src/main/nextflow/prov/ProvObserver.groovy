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

package nextflow.prov

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord
import nextflow.file.FileHelper
import nextflow.file.FileHolder
import nextflow.processor.TaskHandler
import nextflow.exception.AbortOperationException

/**
 * Plugin observer of workflow events
 *
 * @author Bruno Grande <bruno.grande@sagebase.org>
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class ProvObserver implements TraceObserver {

    public static final String DEF_FILE_NAME = 'manifest.json'

    private Map config

    private Path path

    private Boolean overwrite

    private List<PathMatcher> matchers

    private List<Map> published = []

    private Map tasks = [:]

    ProvObserver(Path path, Boolean overwrite, List patterns) {
        this.path = path
        this.overwrite = overwrite
        this.matchers = patterns.collect { pattern ->
            FileSystems.getDefault().getPathMatcher("glob:**/${pattern}")
        }
    }

    @Override
    void onFlowCreate(Session session) {
        this.config = session.config

        // check file existance
        final attrs = FileHelper.readAttributes(path)
        if( attrs ) {
            if( overwrite && (attrs.isDirectory() || !path.delete()) )
                throw new AbortOperationException("Unable to overwrite existing provenance manifest: ${path.toUriString()}")
            else if( !overwrite )
                throw new AbortOperationException("Provenance manifest already exists: ${path.toUriString()}")
        }
    }

    static def jsonify(root) {
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

    void trackProcess(TaskHandler handler, TraceRecord trace) {
        def task = handler.task

        // TODO: Figure out what the '$' input/output means
        //       Omitting them from manifest for now
        def taskMap = [
            'id': task.id as String,
            'name': task.name,
            'cached': task.cached,
            'process': trace.getProcessName(),
            'inputs': task.inputs.findResults { inParam, object -> 
                def inputMap = [ 
                    'name': inParam.getName(),
                    'value': jsonify(object) 
                ] 
                inputMap['name'] == '$' ? null : inputMap
            },
            'outputs': task.outputs.findResults { outParam, object -> 
                def outputMap = [
                    'name': outParam.getName(),
                    'emit': outParam.getChannelEmitName(),
                    'value': jsonify(object) 
                ] 
                outputMap['name'] == '$' ? null : outputMap
            }
        ]

        tasks.put(task.id, taskMap)
    }

    @Override
    void onProcessComplete(TaskHandler handler, TraceRecord trace) {
        trackProcess(handler, trace)
    }

    @Override
    void onProcessCached(TaskHandler handler, TraceRecord trace) {
        trackProcess(handler, trace)
    }

    @Override
    void onFilePublish(Path destination, Path source) {
        boolean match = matchers.isEmpty() || matchers.any { matcher ->
            matcher.matches(destination)
        }

        if( !match )
            return

        published.add( [
            'source': source.toUriString(),
            'target': destination.toUriString()
        ] )
    }

    @Override
    void onFlowComplete() {
        // generate temporary output-task map
        def taskLookup = tasks.inject([:]) { accum, hash, task ->
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

        // add task information to published files
        published.each { path ->
            path['publishingTaskId'] = taskLookup[path.source]
        }

        // save manifest to JSON file
        def manifest = [
            'pipeline': config.manifest,
            'published': published,
            'tasks': tasks
        ]
        def manifestJson = JsonOutput.toJson(manifest)
        def manifestJsonPretty = JsonOutput.prettyPrint(manifestJson)

        path.text = manifestJsonPretty

    }

}
