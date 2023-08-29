# nf-prov

Nextflow plugin for tracking provenance of pipeline output files.

## Getting Started

The `nf-prov` plugin requires Nextflow version `22.11.0-edge` or later.

To enable and configure `nf-prov`, include the following snippet to your Nextflow config and update as needed.

```groovy
plugins {
  id 'nf-prov'
}

prov {
    enabled = true
    overwrite = true
    file = "${params.outdir}/manifest.json"
}
```

Finally, run your Nextflow pipeline. You do not need to modify your pipeline script in order to use the `nf-prov` plugin. The plugin will automatically generate a JSON file with provenance information.

## Configuration

The following options are available:

`prov.enabled`

Create the provenance manifest (default: `true` if plugin is loaded).

`prov.file`

The path of the provenance manifest (default: `manifest.json`).

`prov.format`

The manifest format. Can be `legacy` or `bco` (default: `legacy`).

`prov.metadata`

*Used only by the `bco` format*

Map of additional metadata for the provenance manifest. The following keys are supported:

- `license`

`prov.overwrite`

Overwrite any existing provenance report with the same name (default: `false`).

`prov.patterns`

List of file patterns to include in the provenance manifest, from the set of published files. By default, all published files are included.

## Development

Run the following commands to build and test the nf-prov Nextflow plugin. Refer to the [nf-hello](https://github.com/nextflow-io/nf-hello) README for additional instructions (_e.g._ for publishing the plugin).

```console
# (Optional) Checkout relevant feature branch
# git checkout <branch>

# Create an empty folder for nf-prov and nextflow repos
git clone --depth 1 https://github.com/nextflow-io/nextflow ../nextflow-nf-prov

# Prepare the nextflow repo
cd ../nextflow-nf-prov && ./gradlew compile exportClasspath && cd -

# Prepare the nf-prov repo
grep -v 'includeBuild' settings.gradle > settings.gradle.bkp
echo "includeBuild('../nextflow-nf-prov')" >> settings.gradle.bkp
mv -f settings.gradle.bkp settings.gradle
./gradlew assemble

# Launch
./launch.sh run test.nf -plugins nf-prov
```

## Package, Upload, and Publish

The project should hosted in a GitHub repository whose name should match the name of the plugin,
that is the name of the directory in the `plugins` folder e.g. `nf-synapse` in this project.

Following these step to package, upload and publish the plugin:

1. Create a file named `gradle.properties` in the project root containing the following attributes
   (this file should not be committed in the project repository):

  * `github_organization`: the GitHub organisation the plugin project is hosted
  * `github_username` The GitHub username granting access to the plugin project.
  * `github_access_token`:  The GitHub access token required to upload and commit changes in the plugin repository.
  * `github_commit_email`:  The email address associated with your GitHub account.

2. Update the `Plugin-Version` field in the following file with the release version:

    ```
    plugins/nf-prov/src/resources/META-INF/MANIFEST.MF
    ```

3. Run the following command to package and upload the plugin in the GitHub project releases page:

    ```
    ./gradlew :plugins:nf-prov:upload
    ```

4. Create a pull request against the [nextflow-io/plugins](https://github.com/nextflow-io/plugins/blob/main/plugins.json) 
  project to make the plugin public accessible to Nextflow app. 

