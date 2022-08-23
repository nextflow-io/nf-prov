# quilt-cli

A wrapper CLI for Quilt operations that are used by the `nf-quilt` plugin.

## Installation

`quilt-cli` depends on Python 3.7 or higher.

Install locally for development:
```bash
pip3 install -e .
```

Install directly from GitHub (requires public key authentication):
```bash
pip3 install git+https://github.com/nextflow-io/nf-quilt.git#subdirectory=quilt-cli
```

## Usage

You can use `quilt-cli` locally with the command-line interface (CLI):
```bash
quilt-cli -h
```

Example:
```bash
cat > paths.txt <<EOF
s3://seqera-quilt/pipeline_results/genome/ecoli/Bowtie2Index/
s3://seqera-quilt/pipeline_results/genome/ecoli/genes.gtf
s3://seqera-quilt/pipeline_results/genome/ecoli/genome.fa
file://test.fasta
EOF

quilt-cli push paths.txt genomes/ecoli --registry 's3://seqera-quilt' 
```
