# Additional WRROC configuration

*New in version 1.4.0*

The `wrroc` format supports additional options to configure certain aspects of the Workflow Run RO-Crate. These fields cannot be inferred automatically from the pipeline or the run, and so must be entered through the config.

The following config options are supported:

- `prov.formats.wrroc.agent.contactType`
- `prov.formats.wrroc.agent.email`
- `prov.formats.wrroc.agent.name`
- `prov.formats.wrroc.agent.orcid`
- `prov.formats.wrroc.agent.phone`
- `prov.formats.wrroc.agent.ror`
- `prov.formats.wrroc.organization.contactType`
- `prov.formats.wrroc.organization.email`
- `prov.formats.wrroc.organization.name`
- `prov.formats.wrroc.organization.phone`
- `prov.formats.wrroc.organization.ror`
- `prov.formats.wrroc.publisher`

Refer to the [WRROC User Guide](https://www.researchobject.org/workflow-run-crate/) for more information about the associated RO-Crate entities.

Here is an example config:

```groovy
prov {
    formats {
        wrroc {
            agent {
                name = "John Doe"
                orcid = "https://orcid.org/0000-0000-0000-0000"
                email = "john.doe@example.org"
                phone = "(0)89-99998 000"
                contactType = "Researcher"
            }
            organization {
                name = "University of XYZ"
                ror = "https://ror.org/000000000"
            }
            publisher = "https://ror.org/000000000"
        }
    }
}
```
