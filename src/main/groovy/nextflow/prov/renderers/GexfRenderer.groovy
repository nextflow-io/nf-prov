/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.prov.renderers

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.text.SimpleDateFormat
import javax.xml.XMLConstants
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import nextflow.Session
import nextflow.processor.TaskRun
import nextflow.prov.ProvGexfConfig
import nextflow.prov.Renderer
import nextflow.prov.util.ProvHelper
import nextflow.util.PathNormalizer

/**
 * Renderer for the GEXF graph format.
 *
 * @author Pierre Lindenbaum (Institut du Thorax, Nantes, France)
 */
@CompileStatic
class GexfRenderer implements Renderer {

    private static final String VERSION = "1.3"
    private static final String XMLNS = "http://gexf.net/${VERSION}"
    private static final String SCHEMA_LOCATION = "${XMLNS} ${XMLNS}/gexf.xsd"
    private static final String VIZ_NS = "${XMLNS}/viz"

    private static int CURRENT_ID = 1

    /** attribute node types */
    private static final String ATT_TASK_CACHED = "0"
    private static final String ATT_TASK_ABORTED = "1"
    private static final String ATT_NODE_TYPE = "2"
    private static final String ATT_FILE_PATH = "3"
    private static final String ATT_TASK_PROCESS = "4"
    private static final String ATT_FILE_SIZE = "5"
    private static final String ATT_FILE_TYPE = "6"
    private static final String ATT_FILE_CREATED_TIMESTAMP = "7"
    private static final String ATT_FILE_MODIFIED_TIMESTAMP = "8"

    private Path path

    private boolean overwrite

    @Delegate
    private PathNormalizer normalizer

    GexfRenderer(ProvGexfConfig config) {
        path = (config.file as Path).complete()
        overwrite = config.overwrite

        ProvHelper.checkFileOverwrite(path, overwrite)
    }

    @Override
    void render(Session session, Set<TaskRun> tasks, Map<String,Path> workflowOutputs, Map<Path,Path> publishedFiles) {
        final mainGraph = new GexfGraph()

        for( final task : tasks ) {
            final taskNode = mainGraph.addNode(new TaskGexfNode(task))

            final inputs = ProvHelper.getTaskInputs(task)
            for( final name : inputs.keySet() ) {
                final filein = mainGraph.addNode(new FileGexfNode(name, inputs[name]))
                mainGraph.addEdge(filein, taskNode)
            }

            final outputs = ProvHelper.getTaskOutputs(task)
            for( final output : outputs ) {
                final fileout = mainGraph.addNode(new FileGexfNode(output))
                mainGraph.addEdge(taskNode, fileout)
            }
        }

        mainGraph.write(path)
    }


    @EqualsAndHashCode(includes = 'source,target', includeFields = true)
    private static class GexfEdge {

        private final int uid
        private final GexfNode source
        private final GexfNode target

        GexfEdge(GexfNode source, GexfNode target) {
            this.uid = ++CURRENT_ID
            this.source = source
            this.target = target
        }

        String getId() {
            return "E" + uid
        }

        void write(XMLStreamWriter w) {
            w.writeEmptyElement("edge")
            w.writeAttribute("id", getId())
            w.writeAttribute("type", "directed")
            w.writeAttribute("source", source.getId())
            w.writeAttribute("target", target.getId())
        }

        @Override
        String toString() {
            return getId()
        }
    }


    private static abstract class GexfNode {

        private final int id = ++CURRENT_ID

        int index
        String label
        Map<String,Path> inputs
        List<Path> outputs

        String getId() {
            return "n" + id
        }

        abstract String getLabel()

        abstract void write(XMLStreamWriter w)

        @Override
        int hashCode() {
            return getId().hashCode()
        }

        @Override
        String toString() {
            return getLabel()
        }
    }


    private static class TaskGexfNode extends GexfNode {

        final TaskRun task

        TaskGexfNode(TaskRun task) {
            this.task = task
        }

        @Override
        String getLabel() {
            return task.name
        }

        @Override
        void write(XMLStreamWriter w) {
            w.writeStartElement("node")

            w.writeAttribute("id", getId())
            w.writeAttribute("label", getLabel())

            w.writeStartElement("attvalues")

            w.writeEmptyElement("attvalue")
            w.writeAttribute("for", ATT_TASK_CACHED)
            w.writeAttribute("value", "${task.cached}")

            w.writeEmptyElement("attvalue")
            w.writeAttribute("for", ATT_TASK_ABORTED)
            w.writeAttribute("value", "${task.aborted}")

            w.writeEmptyElement("attvalue")
            w.writeAttribute("for", ATT_NODE_TYPE)
            w.writeAttribute("value", "task")

            w.writeEmptyElement("attvalue")
            w.writeAttribute("for", ATT_TASK_PROCESS)
            w.writeAttribute("value", task.processor.name)

            w.writeEndElement() // attvalues

            w.writeEmptyElement("viz", "shape", VIZ_NS)
            w.writeAttribute("value", "disc")

            w.writeEmptyElement("viz", "color", VIZ_NS)
            w.writeAttribute("hex", "#A7E0E0")

            w.writeEndElement()
        }

        @Override
        boolean equals(Object obj) {
            if( this === obj ) return true
            if( obj === null || !(obj instanceof TaskGexfNode) ) return false
            final n = (TaskGexfNode)obj
            return this.task.name == n.task.name
        }

        @Override
        int hashCode() {
            return getLabel().hashCode()
        }
    }


    @EqualsAndHashCode(includes = 'path', includeFields = true)
    private static class FileGexfNode extends GexfNode {

        final String name
        final Path path

        double vizSize = -1

        FileGexfNode(String name, Path path) {
            this.name = name
            this.path = path
        }

        FileGexfNode(Path path) {
            this(path.getFileName().toString(), path)
        }

        long getFileSize() {
            if( !path.isFile() ) return -1L
            try {
                return Files.size(path)
            }
            catch( IOException e ) {
                return -1L
            }
        }

        long getFileCreatedTimestamp() {
            if( !path.isFile() ) return -1L
            try {
                final fileTime = Files.readAttributes(path, BasicFileAttributes.class).creationTime()
                return fileTime == null ? -1L : fileTime.toMillis()
            }
            catch( IOException e ) {
                return -1L
            }
        }

        long getFileModifiedTimestamp() {
            if( !path.isFile() ) return -1L
            try {
                final fileTime = Files.readAttributes(path, BasicFileAttributes.class).lastModifiedTime()
                return fileTime == null ? -1L : fileTime.toMillis()
            }
            catch( IOException e ) {
                return -1L
            }
        }

        @Override
        String getLabel() {
            return name
        }

        String getFileType() {
            return path.isDirectory()
                ? "directory"
                : fileExtension(name)
        }

        /**
         * Get the extension of a file name.
         *
         * For compressed files with a .gz and .bz2 suffix,
         * use the preceding extension (e.g. *.fastq.gz -> fastq).
         *
         * @param s
         */
        private String fileExtension(String s) {
            if( s.endsWith(".gz") )
                return fileExtension(s.substring(0, s.length() - 3))

            if( s.endsWith(".bz2") )
                return fileExtension(s.substring(0, s.length() - 4))

            int i = s.lastIndexOf(".")
            if( i <= 0 )
                return ""

            return s.substring(i + 1)
        }

        @Override
        void write(XMLStreamWriter w) {
            w.writeStartElement("node")

            w.writeAttribute("id", getId())
            w.writeAttribute("label", getLabel())

            w.writeStartElement("attvalues")

            w.writeEmptyElement("attvalue")
            w.writeAttribute("for", ATT_NODE_TYPE)
            w.writeAttribute("value", "file")

            w.writeEmptyElement("attvalue")
            w.writeAttribute("for", ATT_FILE_PATH)
            w.writeAttribute("value", path.toString())

            final fileSize = getFileSize()
            if( fileSize >= 0L ) {
                w.writeEmptyElement("attvalue")
                w.writeAttribute("for", ATT_FILE_SIZE)
                w.writeAttribute("value", "${fileSize}")
            }

            final fileType = getFileType()
            if( fileType ) {
                w.writeEmptyElement("attvalue")
                w.writeAttribute("for", ATT_FILE_TYPE)
                w.writeAttribute("value", fileType)
            }

            final fileCreatedTimestamp = getFileCreatedTimestamp()
            if( fileCreatedTimestamp >= 0L ) {
                w.writeEmptyElement("attvalue")
                w.writeAttribute("for", ATT_FILE_CREATED_TIMESTAMP)
                w.writeAttribute("value", "${fileCreatedTimestamp}")
            }

            final fileModifiedTimestamp = getFileModifiedTimestamp()
            if( fileModifiedTimestamp >= 0L ) {
                w.writeEmptyElement("attvalue")
                w.writeAttribute("for", ATT_FILE_MODIFIED_TIMESTAMP)
                w.writeAttribute("value", "${fileModifiedTimestamp}")
            }

            w.writeEndElement() // attvalues

            w.writeEmptyElement("viz", "shape", VIZ_NS)
            w.writeAttribute("value", "square")

            w.writeEmptyElement("viz", "color", VIZ_NS)
            w.writeAttribute("hex", "#CA3C66")

            if( vizSize > 0 ) {
                w.writeEmptyElement("viz", "size", VIZ_NS)
                w.writeAttribute("value", "${vizSize}")
            }

            w.writeEndElement()
        }
    }


    private static class GexfGraph {

        private final Set<GexfNode> nodes = new HashSet<>()
        private final Set<GexfEdge> edges = new HashSet<>()

        GexfNode addNode(GexfNode n) {
            nodes.add(n)
            return n
        }

        GexfEdge addEdge(GexfNode source, GexfNode target) {
            final e = new GexfEdge(source, target)
            edges.add(e)
            return e
        }

        void write(Path xmlFile) {
            setVizSizes(nodes)

            Files.newOutputStream(xmlFile).withCloseable { outputStream ->
                write0(outputStream)
            }
        }

        private void write0(OutputStream outputStream) {
            final xof = XMLOutputFactory.newInstance()
            final encoding = "UTF-8"
            final w = xof.createXMLStreamWriter(outputStream, encoding)

            w.writeStartDocument(encoding, "1.0")
            w.writeStartElement("gexf")
            w.writeAttribute("xmlns", XMLNS)
            w.writeAttribute("xmlns:xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI)
            w.writeAttribute("xmlns:viz", VIZ_NS)
            w.writeAttribute("xsi:schemaLocation", SCHEMA_LOCATION)
            w.writeAttribute("version", VERSION)
            w.writeStartElement("meta")
            w.writeAttribute("lastmodifieddate", new SimpleDateFormat("yyyy-MM-dd").format(new Date()))
            w.writeStartElement("creator")
            w.writeCharacters("GEXF Renderer for nf-prov.")
            w.writeEndElement()

            w.writeStartElement("description")
            w.writeCharacters("GEXF Renderer for nf-prov. Visualize with https://gephi.org/.")
            w.writeEndElement()

            w.writeEndElement() // meta
            w.writeStartElement("graph")
            w.writeAttribute("mode", "static")
            w.writeAttribute("defaultedgetype", "directed")

            w.writeStartElement("attributes")
            w.writeAttribute("class", "node")
            w.writeAttribute("mode", "static")

            w.writeStartElement("attribute")
            w.writeAttribute("id", ATT_TASK_CACHED)
            w.writeAttribute("title", "cached")
            w.writeAttribute("type", "boolean")
            w.writeStartElement("default")
            w.writeCharacters("false")
            w.writeEndElement()
            w.writeEndElement()

            w.writeStartElement("attribute")
            w.writeAttribute("id", ATT_TASK_ABORTED)
            w.writeAttribute("title", "aborted")
            w.writeAttribute("type", "boolean")
            w.writeStartElement("default")
            w.writeCharacters("false")
            w.writeEndElement()
            w.writeEndElement()

            w.writeStartElement("attribute")
            w.writeAttribute("id", ATT_NODE_TYPE)
            w.writeAttribute("title", "node_type")
            w.writeAttribute("type", "string")
            w.writeStartElement("default")
            w.writeCharacters("file")
            w.writeEndElement() // default
            w.writeStartElement("options")
            w.writeCharacters("[file, task]")
            w.writeEndElement() // options
            w.writeEndElement() // attribute

            w.writeStartElement("attribute")
            w.writeAttribute("id", ATT_FILE_PATH)
            w.writeAttribute("title", "path")
            w.writeAttribute("type", "string")
            w.writeStartElement("default")
            w.writeCharacters("")
            w.writeEndElement()
            w.writeEndElement()

            w.writeStartElement("attribute")
            w.writeAttribute("id", ATT_TASK_PROCESS)
            w.writeAttribute("title", "processes")
            w.writeAttribute("type", "string")
            w.writeStartElement("default")
            w.writeCharacters("")
            w.writeEndElement()
            w.writeEndElement()

            w.writeStartElement("attribute")
            w.writeAttribute("id", ATT_FILE_SIZE)
            w.writeAttribute("title", "file_size")
            w.writeAttribute("type", "long")
            w.writeStartElement("default")
            w.writeCharacters("-1")
            w.writeEndElement()
            w.writeEndElement()

            w.writeStartElement("attribute")
            w.writeAttribute("id", ATT_FILE_TYPE)
            w.writeAttribute("title", "file_suffix")
            w.writeAttribute("type", "string")
            w.writeStartElement("default")
            w.writeCharacters("")
            w.writeEndElement()
            w.writeEndElement()

            w.writeStartElement("attribute")
            w.writeAttribute("id", ATT_FILE_CREATED_TIMESTAMP)
            w.writeAttribute("title", "created_timestamp")
            w.writeAttribute("type", "long")
            w.writeStartElement("default")
            w.writeCharacters("-1")
            w.writeEndElement()
            w.writeEndElement()

            w.writeStartElement("attribute")
            w.writeAttribute("id", ATT_FILE_MODIFIED_TIMESTAMP)
            w.writeAttribute("title", "modified_timestamp")
            w.writeAttribute("type", "long")
            w.writeStartElement("default")
            w.writeCharacters("-1")
            w.writeEndElement()
            w.writeEndElement()

            w.writeEndElement() // attributes

            w.writeStartElement("attributes")
            w.writeAttribute("class", "edge")
            w.writeAttribute("mode", "static")
            w.writeEndElement() // attributes

            w.writeStartElement("nodes")
            for( final n : nodes ) {
                n.write(w)
            }
            w.writeEndElement() // nodes

            w.writeStartElement("edges")
            for( final e : edges ) {
                e.write(w)
            }
            w.writeEndElement() // edges

            w.writeEndElement() // graph
            w.writeEndElement() // gexf
            w.writeEndDocument()
            w.flush()
        }

        /**
         * Set the viz size for file nodes to a normalzied value
         * based on the file size, ranging from 10 to 100.
         *
         * @param nodes
         */
        private static void setVizSizes(Set<GexfNode> nodes) {
            final fileSizes = nodes
                .findAll { n -> n instanceof FileGexfNode }
                .inject([:]) { acc, n ->
                    final fn = (FileGexfNode) n
                    acc[fn] = fn.getFileSize()
                    acc
                } as Map<FileGexfNode, Long>

            if( fileSizes.isEmpty() )
                return

            final minFileSize = fileSizes.values().min()
            final maxFileSize = fileSizes.values().max()

            if( minFileSize >= maxFileSize )
                return

            fileSizes.forEach((fn, fileSize) -> {
                if( fileSize == -1L )
                    return
                fn.vizSize = 10.0 + ((fileSize - minFileSize) / (double)(maxFileSize - minFileSize)) * 90.0
            })
        }
    }

}
