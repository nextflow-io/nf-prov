
params.constant = "foo"

process ECHO_SCRIPT {
    tag "${prefix}"
    publishDir params.outdir, mode: 'copy'

    input:
    tuple val(prefix), val(constant)

    output:
    tuple val(prefix), val(constant), path("*.txt")

    script:
    """
    echo \$RANDOM > ${prefix}.${constant}.1.txt
    echo \$RANDOM > ${prefix}.${constant}.2.txt
    """
}

process ECHO_EXEC {
    tag "${prefix}"
    publishDir params.outdir, mode: 'copy'

    input:
    tuple val(prefix), val(constant)

    output:
    path(outfile), emit: txt

    exec:
    outfile = "${prefix}.exec.txt"
    task.workDir.resolve(outfile).write(prefix)
}

workflow {
    prefixes_ch = channel.from('r1', 'r2', 'r3')
    constant_ch = channel.of(params.constant)
    inputs_ch   = prefixes_ch.combine(constant_ch)
    ECHO_SCRIPT(inputs_ch)
    ECHO_EXEC(inputs_ch)
}
