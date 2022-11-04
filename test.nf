nextflow.enable.dsl=2

process RNG {

    publishDir "outputs/", mode: 'copy'

    input:
    val prefix

    output:
    path "${prefix}.txt"

    script:
    """
    echo \$RANDOM > ${prefix}.txt
    """

}

workflow {
    channel.from('r1', 'r2', 'r3') | RNG
}
