# nf-prov

Nextflow plugin to render provenance reports for pipeline runs. Supports standard formats such as [BioCompute Object](https://biocomputeobject.org/) and [Workflow Run RO-Crate](https://www.researchobject.org/workflow-run-crate/).

## Requirements

| Version |	Minimum Nextflow version |
| ------- | ------------------------ |
| 1.6.x   | 25.10 |
| 1.5.x   | 25.04 |
| 1.4.x   | 24.10 |
| 1.3.x   | 24.10 |
| 1.2.x   | 23.04 |
| 1.1.x   | 23.04 |
| 1.0.x   | 22.04 |

## Getting Started

To enable and configure `nf-prov`, include the following snippet to your Nextflow config and update as needed.

```groovy
plugins {
  id 'nf-prov'
}

prov {
  enabled = true
  formats {
    bco {
      file = 'bco.json'
      overwrite = true
    }
    wrroc {
      file = 'ro-crate-metadata.json'
      overwrite = true
    }
  }
}
```

Any number of formats can be specified. You do not need to modify your pipeline script in order to use the `nf-prov` plugin.

When you run your Nextflow pipeline, the plugin will automatically produce the specified provenance reports at the end of the run.

## Configuration

*The `file`, `format`, and `overwrite` options have been deprecated since version 1.2.0. Use `formats` instead.*

*The `legacy` format was removed in version 1.5.0. Consider using [data lineage](https://nextflow.io/docs/latest/data-lineage.html) instead.*

The following options are available:

`prov.enabled`

Create the provenance report (default: `true` if plugin is loaded).

`prov.formats`

Configuration scope for the desired output formats. The following formats are available:

- `bco`: Render a [BioCompute Object](https://biocomputeobject.org/). Supports the `file` and `overwrite` options. See [BCO.md](docs/BCO.md) for more information about the additional config options for BCO.

- `dag`: Render the task graph as a Mermaid diagram embedded in an HTML document. Supports the `file` and `overwrite` options.

*New in version 1.4.0*

- `wrroc`: Render a [Workflow Run RO-Crate](https://www.researchobject.org/workflow-run-crate/). Includes all three profiles (Process, Workflow, and Provenance). See [WRROC.md](docs/WRROC.md) for more information about the additional config options for WRROC.

See the [nf-prov-test](./nf-prov-test) directory for an example pipeline that produces every provenance format.

*New in version 1.7.0*

- `gexf`: Render the workflow run as a [GEXF](https://gexf.net/) document. Supports the `file` and `overwrite` options.

`prov.patterns`

List of file patterns to include in the provenance report, from the set of published files. By default, all published files are included.
