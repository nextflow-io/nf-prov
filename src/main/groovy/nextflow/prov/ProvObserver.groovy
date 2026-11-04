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
import nextflow.prov.renderers.GexfRenderer
import nextflow.prov.renderers.WrrocRenderer
import nextflow.prov.util.ProvHelper
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

    private Session session

    private List<Renderer> renderers

    private List<PathMatcher> matchers

    private Set<TaskRun> tasks = []

    private Map<String,List<Path>> workflowOutputs

    // map of target to source, since a source can be published to multiple targets
    private Map<Path,Path> publishedFiles = [:]

    private Lock lock = new ReentrantLock()

    ProvObserver(ProvFormatsConfig formats, List<String> patterns, Path directory=null) {
        this.renderers = createRenderers(formats, directory)
        this.matchers = patterns.collect( pattern ->
            FileSystems.getDefault().getPathMatcher("glob:**/${pattern}")
        )
    }

    private List<Renderer> createRenderers(ProvFormatsConfig config, Path directory) {
        final List<Renderer> result = []

        if( config.bco )
            result.add(new BcoRenderer(config.bco, directory))

        if( config.dag )
            result.add(new DagRenderer(config.dag, directory))

        if( config.gexf )
            result.add(new GexfRenderer(config.gexf, directory))

        if( config.wrroc )
            result.add(new WrrocRenderer(config.wrroc, directory))

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
        if( !ProvHelper.isSuccess(task) )
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

        final paths = event.index
            ? [event.index]
            : ProvHelper.getPaths(event.value)

        if( !paths ) {
            log.warn "Workflow output `${event.name}` does not contain any files and will not be included in provenance reports"
            return
        }

        workflowOutputs[event.name] = paths
    }

    @Override
    void onFilePublish(FilePublishEvent event) {
        final match = matchers.isEmpty() || matchers.any { matcher -> matcher.matches(event.target) }
        if( !match )
            return

        lock.withLock {
            publishedFiles[event.target] = event.source
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
            case GexfRenderer -> 'GEXF';
            case WrrocRenderer -> 'WRROC';
            default -> null;
        }
    }

}
