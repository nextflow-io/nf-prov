# nf-prov

Nextflow plugin to render provenance reports for pipeline runs. Now supporting [BioCompute Objects](https://biocomputeobject.org/)!

## Getting Started

The `nf-prov` plugin requires Nextflow version `23.04.0` or later.

To enable and configure `nf-prov`, include the following snippet to your Nextflow config and update as needed.

```groovy
plugins {
  id 'nf-prov'
}

prov {
  enabled = true
  formats {
    legacy {
      file = 'manifest.json'
      overwrite = true
    }
  }
}
```

Finally, run your Nextflow pipeline. You do not need to modify your pipeline script in order to use the `nf-prov` plugin. The plugin will automatically generate a JSON file with provenance information.

## Configuration

*The `file`, `format`, and `overwrite` options have been deprecated since version 1.2.0. Use `formats` instead.*

The following options are available:

`prov.enabled`

Create the provenance report (default: `true` if plugin is loaded).

`prov.formats`

*New in version 1.2.0*

Configuration scope for the desired output formats. The following formats are available:

- `bco`: Render a [BioCompute Object](https://biocomputeobject.org/). Supports the `file` and `overwrite` options.

  Visit the [BCO User Guide](https://docs.biocomputeobject.org/user_guide/) to learn more about this format and how to extend it with information that isn't available to Nextflow.

- `dag`: Render the task graph as a Mermaid diagram embedded in an HTML document. Supports the `file` and `overwrite` options.

- `legacy`: Render the legacy format originally defined in this plugin (default). Supports the `file` and `overwrite` options.

Any number of formats can be specified, for example:

```groovy
prov {
  formats {
    bco {
      file = 'bco.json'
      overwrite = true
    }
    legacy {
      file = 'manifest.json'
      overwrite = true
    }
  }
}
```

`prov.patterns`

List of file patterns to include in the provenance report, from the set of published files. By default, all published files are included.

## Development

Run the following commands to build and test the nf-prov Nextflow plugin. Refer to the [nf-hello](https://github.com/nextflow-io/nf-hello) README for additional instructions (_e.g._ for publishing the plugin).

```bash
# (Optional) Checkout relevant feature branch
# git checkout <branch>

# Create an empty folder for nf-prov and nextflow repos
git clone --depth 1 https://github.com/nextflow-io/nextflow ../nextflow

# Prepare the nextflow repo
cd ../nextflow && ./gradlew compile exportClasspath && cd -

# Prepare the nf-prov repo
grep -v 'includeBuild' settings.gradle > settings.gradle.bkp
echo "includeBuild('../nextflow')" >> settings.gradle.bkp
mv -f settings.gradle.bkp settings.gradle
./gradlew assemble

# Launch
./launch.sh run test.nf -plugins nf-prov
```

## Package, Upload, and Publish

The project should hosted in a GitHub repository whose name should match the name of the plugin,
that is the name of the directory in the `plugins` folder e.g. `nf-prov` in this project.

Following these step to package, upload and publish the plugin:

1. Create a file named `gradle.properties` in the project root containing the following attributes
   (this file should not be committed in the project repository):

  * `github_organization`: the GitHub organisation the plugin project is hosted
  * `github_username` The GitHub username granting access to the plugin project.
  * `github_access_token`:  The GitHub access token required to upload and commit changes in the plugin repository.
  * `github_commit_email`:  The email address associated with your GitHub account.

2. Update the `Plugin-Version` field in the following file with the release version:

    ```bash
    plugins/nf-prov/src/resources/META-INF/MANIFEST.MF
    ```

3. Run the following command to package and upload the plugin in the GitHub project releases page:

    ```bash
    ./gradlew :plugins:nf-prov:upload
    ```

4. Create a pull request against the [nextflow-io/plugins](https://github.com/nextflow-io/plugins/blob/main/plugins.json) 
  project to make the plugin public accessible to Nextflow app. 

