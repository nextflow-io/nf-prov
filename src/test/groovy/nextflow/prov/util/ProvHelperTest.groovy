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

package nextflow.prov.util

import java.nio.file.Path

import nextflow.processor.TaskRun
import nextflow.script.ScriptType
import spock.lang.Specification
import spock.lang.Unroll

/**
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ProvHelperTest extends Specification {

    @Unroll
    def 'should determine whether #type task with exit status #exitStatus and error #error is successful' () {
        given:
        final task = new TaskRun(type: type, exitStatus: exitStatus, error: error)

        expect:
        ProvHelper.isSuccess(task) == success

        where:
        type                 | exitStatus        | error                 | success
        ScriptType.SCRIPTLET | 0                 | null                  | true
        ScriptType.SCRIPTLET | 1                 | null                  | false
        ScriptType.SCRIPTLET | Integer.MAX_VALUE | null                  | false
        ScriptType.GROOVY    | Integer.MAX_VALUE | null                  | true
        ScriptType.GROOVY    | Integer.MAX_VALUE | new Exception('boom') | false
    }

    def 'should get output files of a typed task' () {
        given:
        def task = Spy(TaskRun)
        task.hasTypedInputsOutputs() >> true
        task.outputFiles = [Path.of('/work/ab/a.txt'), Path.of('/work/ab/outdir')] as Set

        expect:
        ProvHelper.getTaskOutputs(task) == [Path.of('/work/ab/a.txt'), Path.of('/work/ab/outdir')]
    }

    def 'should find task output containing a path' () {
        given:
        def task = Mock(TaskRun)
        def lookup = [(Path.of('/work/ab/outdir')): task]

        expect:
        ProvHelper.findTaskOutput(Path.of('/work/ab/outdir'), lookup) == Path.of('/work/ab/outdir')
        ProvHelper.findTaskOutput(Path.of('/work/ab/outdir/sub/a.txt'), lookup) == Path.of('/work/ab/outdir')
        ProvHelper.findTaskOutput(Path.of('/work/ab/other.txt'), lookup) == null
        ProvHelper.findTaskOutput(Path.of('/data/a.txt'), lookup) == null
    }

    def 'should find params whose path contains a path' () {
        given:
        def paramPaths = ProvHelper.getParamPaths([
            reads: '/data/reads.fq',
            refs: Path.of('/data/refs'),
            index: '/data/refs/index',
            blank: '',
            count: 8,
            samples: [[fastq: '/data/a.fq'], [fastq: Path.of('/data/b.fq')]],
        ])

        expect:
        paramPaths.keySet() == [Path.of('/data/reads.fq'), Path.of('/data/refs'), Path.of('/data/refs/index'), Path.of('/data/a.fq'), Path.of('/data/b.fq')] as Set
        ProvHelper.findParams(Path.of('/data/reads.fq'), paramPaths) == ['reads']
        ProvHelper.findParams(Path.of('/data/refs/index/genome.idx'), paramPaths) == ['index', 'refs']
        ProvHelper.findParams(Path.of('/data/b.fq'), paramPaths) == ['samples']
        ProvHelper.findParams(Path.of('/data/other.txt'), paramPaths) == []
    }

}
