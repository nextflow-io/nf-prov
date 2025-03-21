
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

process WC_SAMPLE {
    input:
    tuple val(id), path(fastq_1), path(fastq_2)

    script:
    """
    wc -l ${fastq_1}
    wc -l ${fastq_2}
    """
}

workflow {
    prefixes_ch = channel.of('r1', 'r2', 'r3')
    constant_ch = channel.value(params.constant)
    inputs_ch   = prefixes_ch.combine(constant_ch)
    ECHO_SCRIPT(inputs_ch)
    ECHO_EXEC(inputs_ch)

    samples_ch = channel.fromPath(params.input).splitCsv(header: true)
    WC_SAMPLE(samples_ch)
}
