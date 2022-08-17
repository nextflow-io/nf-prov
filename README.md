# nf-quilt

Nextflow plugin for interacting with [Quilt](https://quiltdata.com/) packages.

`nf-quilt` currently allows you to publish the outputs of a workflow run as a Quilt package. WHen you launch a pipeline with the `nf-quilt` plugin, it will publish a Quilt package upon workflow completion that contains output files published to S3.

## Getting Started

To use the `nf-quilt` plugin, you need Nextflow 22.04 (or later) and Python 3.7 (or later).

Install the [quilt-api](https://github.com/seqeralabs/quilt-api) package:
```bash
pip3 install git+ssh://git@github.com/seqeralabs/quilt-api.git
```

Add the following snippet to your `nextflow.config` to enable the plugin:
```groovy
plugins {
    id 'nf-quilt'
}
```

Configure the plugin with the `quilt` config scope in your `nextflow.config`. At a minimum, you should specify the package name and registry. You can also specify a list of paths to include in the Quilt package; by default, the plugin will include all output files that were published to S3.

Here's an example based on `nf-core/rnaseq`:
```groovy
quilt {
  packageName = 'genomes/yeast'
  registry = 's3://seqera-quilt'
  message = 'My commit message'
  meta = [pipeline: 'nf-core/rnaseq']
  force = false

  paths = [
    'multiqc_report.html',
    '**/star_salmon/**/deseq2.plots.pdf',
    '**/star_salmon/salmon.merged.gene_counts.tsv',
    '**/star_salmon/salmon.merged.gene_tpm.tsv',
    '**/star_salmon/salmon.merged.transcript_counts.tsv',
    '**/star_salmon/salmon.merged.transcript_tpm.tsv'
  ]
}
```

Finally, run your Nextflow pipeline with your config file. You do not need to modify your pipeline script in order to use the `nf-quilt` plugin. As long as your pipeline publishes the desired output files to S3, the plugin will automatically publish a Quilt package based on your configuration settings.

## Reference

The plugin exposes a new `quilt` config scope which supports the following options:

| Config option 	    | Description 	            |
|---	                |---	                      |
| `quilt.packageName` | Name of package, in the USER/PKG format
| `quilt.registry`    | Registry where to create the new package
| `quilt.message`     | The commit message for the new package
| `quilt.meta`        | Package-level metadata in the form of key-value pairs
| `quilt.force`       | Skip the parent top hash check and create a new revision even if your local state is behind the remote registry
| `quilt.paths`       | List of published files (can be path or glob) to include in the package

## Development

Refer to the [nf-hello](https://github.com/nextflow-io/nf-hello) README for instructions on how to build, test, and publish Nextflow plugins.
