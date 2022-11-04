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
./gradlew compileGroovy
./gradlew assemble

# Launch
./launch.sh run test.nf -plugins nf-prov
```
