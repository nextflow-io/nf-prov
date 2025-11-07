# nf-prov

Contributions are welcome. Fork [this repository](https://github.com/nextflow-io/nf-prov) and open a pull request to propose changes. Consider submitting an [issue](https://github.com/nextflow-io/nf-prov/issues/new) to discuss any proposed changes with the maintainers before submitting a pull request.

## Development

Build and install the plugin to your local Nextflow installation:

```bash
make install
```

Run with Nextflow as usual:

```bash
nextflow run nf-prov-test -plugins nf-prov@<version>
```

## Publishing

Follow these steps to package, upload, and publish the plugin:

1. Update the [version file](./VERSION).

2. Update the [changelog](./CHANGELOG.md).

3. Run `make release` to build and publish the plugin.

4. Make a [GitHub release](https://github.com/nextflow-io/nf-prov/releases).
