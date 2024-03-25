nextflow.enable.dsl=2

process RNG {
    tag "${prefix}"
    publishDir "${params.outdir}", mode: 'copy'

    input:
    tuple val(prefix), val(constant)

    output:
    tuple val(prefix), val(constant), emit: 'values'
    path "*.txt", emit: 'file'

    script:
    """
    echo \$RANDOM > ${prefix}.${constant}.1.txt
    echo \$RANDOM > ${prefix}.${constant}.2.txt
    """

}

// shows nf-prov behavior with exec tasks
process EXEC_FOO {
    tag "${prefix}"
    publishDir "${params.outdir}", mode: 'copy'

    input:
    tuple val(prefix), val(constant)

    output:
    path(outputfile)

    exec:
    outputfile = new File("${task.workDir}/${prefix}.exec_foo.txt")
    outputfile.write(prefix)
}

workflow {
    prefixes_ch = channel.from('r1', 'r2', 'r3')
    constant_ch = channel.of(params.constant)
    inputs_ch   = prefixes_ch.combine(constant_ch)
    RNG(inputs_ch)
    RNG.output.values.view()
    EXEC_FOO(inputs_ch)
}
