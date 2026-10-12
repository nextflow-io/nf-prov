nextflow.enable.types = true

process COUNT_LINES {
    input:
    txt: Path

    output:
    file("${txt.baseName}.count")

    script:
    """
    wc -l < ${txt} > ${txt.baseName}.count
    """
}
