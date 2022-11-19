nextflow.enable.dsl=2

params.constant = "foo"

process RNG {

    publishDir "out/", mode: 'copy'

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

workflow {
    prefixes_ch = channel.from('r1', 'r2', 'r3')
    constant_ch = channel.of(params.constant)
    inputs_ch   = prefixes_ch.combine(constant_ch)
    RNG(inputs_ch)
    RNG.output.values.view()
}
