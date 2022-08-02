# nf-quilt

Nextflow plugin for interacting with [Quilt](https://quiltdata.com/) packages.

`nf-quilt` currently allows you to publish the outputs of a workflow run as a Quilt package. WHen you launch a pipeline with the `nf-quilt` plugin, it will collect every output file that is published to S3 and publish a Quilt package upon workflow completion.

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

## Configuration

The plugin adds a new `quilt` config scope which supports the following options:

| Config option 	    | Description 	            |
|---	                |---	                      |
| `quilt.packageName` | Name of package, in the USER/PKG format
| `quilt.registry`    | Registry where to create the new package
| `quilt.message`     | The commit message for the new package
| `quilt.meta`        | Package-level metadata in the form of key-value pairs
| `quilt.force`       | Skip the parent top hash check and create a new revision even if your local state is behind the remote registry.

For example:
```
quilt {
  packageName = 'genomes/ecoli'
  registry = 's3://seqera-quilt'
  message = 'My commit message'
  meta = [key: 'value']
  force = false
}
```

## Development

Refer to the [nf-hello](https://github.com/nextflow-io/nf-hello) README for instructions on how to build, test, and publish Nextflow plugins.
