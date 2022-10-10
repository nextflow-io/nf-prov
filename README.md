# nf-prov

Nextflow plugin for tracking provenance of pipeline output files.

## Getting Started

To use the `nf-prov` plugin, you need Nextflow 22.04 (or later).

Add the following snippet to your `nextflow.config` to enable the plugin:
```groovy
plugins {
    id 'nf-prov'
}
```

Finally, run your Nextflow pipeline. You do not need to modify your pipeline script in order to use the `nf-prov` plugin. The plugin will automatically generate a JSON file with provenance information.

## Development

Refer to the [nf-hello](https://github.com/nextflow-io/nf-hello) README for instructions on how to build, test, and publish Nextflow plugins.
