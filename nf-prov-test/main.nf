
params.constant = "foo"

process ECHO_SCRIPT {
    tag "${prefix}"

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

    output:
    tuple val(id), path('counts.txt')

    script:
    """
    touch counts.txt
    wc -l ${fastq_1} >> counts.txt
    wc -l ${fastq_2} >> counts.txt
    """
}

workflow {
    main:
    prefixes_ch = channel.of('r1', 'r2', 'r3')
    constant_ch = channel.value(params.constant)
    inputs_ch   = prefixes_ch.combine(constant_ch)
    ECHO_SCRIPT(inputs_ch)
    ECHO_EXEC(inputs_ch)

    samples_ch = channel.fromPath(params.input).splitCsv(header: true)
    counts_ch = WC_SAMPLE(samples_ch)

    publish:
    script = ECHO_SCRIPT.out
    exec = ECHO_EXEC.out
    counts = counts_ch
}

output {
    script {
        path 'script'
        index {
            path 'script.json'
        }
    }

    exec {
        path 'exec'
        index {
            path 'exec.json'
        }
    }

    counts {
        path '.'
    }
}
