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
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord

/**
 * Plugin observer of workflow events
 *
 * @author Bruno Grande <bruno.grande@sagebase.org>
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class ProvObserver implements TraceObserver {

    public static final List<String> VALID_FORMATS = ['bco', 'dag', 'legacy', 'wrroc']

    private Session session

    private List<Renderer> renderers

    private List<PathMatcher> matchers

    private Set<TaskRun> tasks = []

    private Map<Path,Path> workflowOutputs = [:]

    private Lock lock = new ReentrantLock()

    ProvObserver(Map<String,Map> formats, List<String> patterns) {
        this.renderers = formats.collect( (name, config) -> createRenderer(name, config) )
        this.matchers = patterns.collect( pattern ->
            FileSystems.getDefault().getPathMatcher("glob:**/${pattern}")
        )
    }

    private Renderer createRenderer(String name, Map opts) {
        if( name == 'bco' )
            return new BcoRenderer(opts)

        if( name == 'dag' )
            return new DagRenderer(opts)

        if( name == 'legacy' )
            return new LegacyRenderer(opts)

        if( name == 'wrroc' )
            return new WrrocRenderer(opts)

        throw new IllegalArgumentException("Invalid provenance format -- valid formats are ${VALID_FORMATS.join(', ')}")
    }

    @Override
    void onFlowCreate(Session session) {
        this.session = session
    }

    @Override
    void onProcessComplete(TaskHandler handler, TraceRecord trace) {
        // skip failed tasks
        final task = handler.task
        if( !task.isSuccess() )
            return

        lock.withLock {
            tasks << task
        }
    }

    @Override
    void onProcessCached(TaskHandler handler, TraceRecord trace) {
        lock.withLock {
            tasks << handler.task
        }
    }

    @Override
    void onFilePublish(Path destination, Path source) {
        final match = matchers.isEmpty() || matchers.any { matcher -> matcher.matches(destination) }
        if( !match )
            return

        lock.withLock {
            workflowOutputs[source] = destination
        }
    }

    @Override
    void onFlowComplete() {
        if( !session.isSuccess() )
            return

        for( final renderer : renderers )
            renderer.render(session, tasks, workflowOutputs)
    }

}
