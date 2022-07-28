# nf-quilt

This plugin provides an integration for Nextflow with [Quilt](https://quiltdata.com/).

It currently allows you to publish a Quilt package for a workflow run that contains all of the output files that were published to S3.

## Requirements

- Nextflow 22.04 or later
- Python 3.7 or later
- The [quilt-api](https://github.com/seqeralabs/quilt-api) package

## Usage

To enable the plugin, add the following snippet to your `nextflow.config`:
```
plugins {
    id 'nf-quilt'
}
```

The plugin also adds a new `quilt` config scope which supports the following options:

| Config option 	  | Description 	            |
|---	              |---	                        |
| `quilt.packageName` | Name of package, in the USER/PKG format
| `quilt.registry`    | Registry where to create the new package
| `quilt.force`       | Skip the parent top hash check and create a new revision even if your local state is behind the remote registry.

For example:
```
quilt {
  packageName = 'genomes/ecoli'
  registry = 's3://seqera-quilt'
}
```

WHen you launch a pipeline with the `nf-quilt` plugin, it will collect every output file that is published to S3 and publish a Quilt package upon workflow completion.
