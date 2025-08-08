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
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskRun
import nextflow.prov.renderers.BcoRenderer
import nextflow.prov.renderers.DagRenderer
import nextflow.prov.renderers.WrrocRenderer
import nextflow.trace.TraceObserverV2
import nextflow.trace.event.FilePublishEvent
import nextflow.trace.event.TaskEvent
import nextflow.trace.event.WorkflowOutputEvent

/**
 * Plugin observer of workflow events
 *
 * @author Bruno Grande <bruno.grande@sagebase.org>
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class ProvObserver implements TraceObserverV2 {

    public static final List<String> VALID_FORMATS = ['bco', 'dag', 'legacy', 'wrroc']

    private Session session

    private List<Renderer> renderers

    private List<PathMatcher> matchers

    private Set<TaskRun> tasks = []

    private Map<String,Path> workflowOutputs

    private Map<Path,Path> publishedFiles = [:]

    private Lock lock = new ReentrantLock()

    ProvObserver(ProvFormatsConfig formats, List<String> patterns) {
        this.renderers = createRenderers(formats)
        this.matchers = patterns.collect( pattern ->
            FileSystems.getDefault().getPathMatcher("glob:**/${pattern}")
        )
    }

    private List<Renderer> createRenderers(ProvFormatsConfig config) {
        final List<Renderer> result = []

        if( config.bco )
            result.add(new BcoRenderer(config.bco))

        if( config.dag )
            result.add(new DagRenderer(config.dag))

        if( config.wrroc )
            result.add(new WrrocRenderer(config.wrroc))

        return result
    }

    @Override
    void onFlowCreate(Session session) {
        this.session = session
    }

    @Override
    void onTaskComplete(TaskEvent event) {
        // skip failed tasks
        final task = event.handler.task
        if( !task.isSuccess() )
            return

        lock.withLock {
            tasks << task
        }
    }

    @Override
    void onTaskCached(TaskEvent event) {
        lock.withLock {
            tasks << event.handler.task
        }
    }

    @Override
    void onWorkflowOutput(WorkflowOutputEvent event) {
        if( workflowOutputs == null )
            workflowOutputs = [:]

        final value = event.value instanceof Path
            ? event.value as Path
            : event.index

        if( !value )
            log.warn "Workflow output `${event.name}` should either be a single path or declare an index file in order to be included in provenance reports"

        workflowOutputs[event.name] = value
    }

    @Override
    void onFilePublish(FilePublishEvent event) {
        final match = matchers.isEmpty() || matchers.any { matcher -> matcher.matches(event.target) }
        if( !match )
            return

        lock.withLock {
            publishedFiles[event.source] = event.target
        }
    }

    @Override
    void onFlowComplete() {
        if( !session.isSuccess() )
            return

        for( final renderer : renderers ) {
            try {
                renderer.render(session, tasks, workflowOutputs, publishedFiles)
            }
            catch( Exception e ) {
                log.warn "Error occurred while rendering ${rendererName(renderer)} provenance report -- see Nextflow log for details"
                log.debug "${e}"
            }
        }
    }

    private static String rendererName(Renderer renderer) {
        return switch( renderer ) {
            case BcoRenderer -> 'BCO';
            case DagRenderer -> 'DAG';
            case WrrocRenderer -> 'WRROC';
            default -> null;
        }
    }

}
