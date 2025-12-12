/*
 * Copyright 2023, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import nextflow.Session
import nextflow.processor.TaskRun
import nextflow.prov.ProvGexfConfig
import nextflow.prov.Renderer
import nextflow.prov.util.ProvHelper
import nextflow.script.WorkflowMetadata
import nextflow.util.PathNormalizer
import nextflow.util.StringUtils
import javax.xml.stream.XMLOutputFactory
import javax.xml.XMLConstants
import javax.xml.stream.XMLStreamWriter
import java.text.SimpleDateFormat

/**
 * Renderer for the GEXF graph format.
 *
 * @author Pierre Lindenbaum
 */
@CompileStatic
class GexfRenderer implements Renderer {
  private static final String VERSION="1.3";
  private static final String XMLNS="http://www.gexf.net/1.3";
  private static final String XMLNS_VIZ= XMLNS + "/viz";
  private static final String SCHEMA_LOCATION = "http://www.gexf.net/1.3 http://www.gexf.net/1.3/gexf.xsd";
  private static final String VIZ_NS = "http://gexf.net/1.3/viz";
  private static int ID_GENERATOR=1;

  /** attribute node types */
  private static final String ATT_CACHED = "0";
  private static final String ATT_ABORTED = "1";
  private static final String ATT_NODE_TYPE = "2";
  private static final String ATT_FULLPATH = "3";
  private static final String ATT_PROC_NAME = "4";
  private static final String ATT_FILESIZE = "5";
  private static final String ATT_FILETYPE = "6";

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
    // get workflow metadata
    //final metadata = session.workflowMetadata
    //this.normalizer = new PathNormalizer(metadata)

    try {
      final GexfGraph mainGraph = new GexfGraph();
      for( final task : tasks ) {
        final GexfNode taskNode = mainGraph.addNode(new TaskRunGexfNode(task));

        final Map<String,Path> inputs = ProvHelper.getTaskInputs(task)
        for(String k: inputs.keySet()) {
          final  GexfNode filein= mainGraph.addNode(new FileGexfNode(k,inputs[k]));
          mainGraph.addEdge(filein,taskNode);
        }
        final List<Path> outputs = ProvHelper.getTaskOutputs(task)
        for(Path k: outputs) {
          final  GexfNode fileout= mainGraph.addNode(new FileGexfNode(k));
          mainGraph.addEdge(taskNode,fileout);
        }
      }
      mainGraph.writeXml(this.path);
    }
    catch(Throwable err) {
      err.printStackTrace();
    }
  }



  static class GexfEdge {
    private final int uid = ++ID_GENERATOR;
    private final GexfNode source;
    private final GexfNode target;

    GexfEdge(final GexfNode source, final GexfNode target) {
      this.source= source;
      this.target = target;
    }


    String getId() {
      return "E" + uid;
    }

    void write(XMLStreamWriter w) {
      w.writeEmptyElement("edge");
      w.writeAttribute("id",getId());
      w.writeAttribute("type","directed");
      w.writeAttribute("source",source.getId());
      w.writeAttribute("target",target.getId());
    }
    @Override
    public int hashCode() {
      int n= this.source.hashCode()%31;
      n = n * this.target.hashCode();
      return n%31;
    }

    @Override
    public boolean equals(Object obj) {
      if( this=== obj) return true;
      if( obj===null || !(obj instanceof GexfEdge)) return false;
      final GexfEdge e = (GexfEdge)obj;
      return this.source.equals(e.source) && this.target.equals(e.target);
    }
    @Override
    public String toString() {
      return getId();
    }
  }

  private static abstract class GexfNode {
    private final int id = ++ID_GENERATOR;
    int index
    String label
    Map<String,Path> inputs
    List<Path> outputs
    String getId() {
      return "n" + this.id;
    }
    abstract String getLabel();


    @Override
    public int hashCode() {
      return getId().hashCode();
    }

    abstract void write(XMLStreamWriter w)

    @Override
    String toString() {
      return getLabel();
    }
  }

  private static class TaskRunGexfNode extends GexfNode  {
    final TaskRun taskRun;
    TaskRunGexfNode(final TaskRun taskRun) {
      this.taskRun = taskRun;
    }
    @Override
    public boolean equals(Object obj) {
      if( this=== obj) return true;
      if( obj===null || !(obj instanceof TaskRunGexfNode)) return false;
      final TaskRunGexfNode n = (TaskRunGexfNode)obj;
      return this.taskRun.equals(n.taskRun);
    }

    @Override
    public String getLabel() {
      return taskRun.name;
    }
    @Override
    void write(XMLStreamWriter w) {
      w.writeStartElement("node");

      w.writeAttribute("id",getId());
      w.writeAttribute("label",getLabel());
      w.writeStartElement("attvalues");
      w.writeEmptyElement("attvalue");
      w.writeAttribute("for",ATT_CACHED);
      w.writeAttribute("value",String.valueOf(taskRun.cached));
      w.writeEmptyElement("attvalue");
      w.writeAttribute("for",ATT_ABORTED);
      w.writeAttribute("value",String.valueOf(taskRun.aborted));
      w.writeEmptyElement("attvalue");
      w.writeAttribute("for",ATT_NODE_TYPE);
      w.writeAttribute("value","task");
      w.writeEmptyElement("attvalue");
      w.writeAttribute("for",ATT_PROC_NAME);
      w.writeAttribute("value",taskRun.processor.name);


      w.writeEndElement();//attvalues

      w.writeEmptyElement("viz","shape", VIZ_NS);
      w.writeAttribute("value","disc");

      w.writeEmptyElement("viz","color", VIZ_NS);
      w.writeAttribute("hex","#A7E0E0");


      w.writeEndElement();
    }

    @Override
    public int hashCode() {
      return getLabel().hashCode();
    }
  }
  private static class FileGexfNode extends GexfNode  {
    final String noDirName;
    final Path fullPath;
    double viz_size = -1;
    FileGexfNode(final String noDirName,final Path fullPath) {
      this.noDirName =noDirName;
      this.fullPath = fullPath;
    }
    FileGexfNode(final Path path) {
      this(path.getFileName().toString(),path);
    }
    @Override
    public boolean equals(Object obj) {
      if( this === obj) return true;
      if( obj=== null || !(obj instanceof FileGexfNode)) return false;
      final FileGexfNode n = (FileGexfNode)obj;
      return this.fullPath.equals(n.fullPath);
    }

    public boolean isDirectory() {
      try {
        return Files.isDirectory(this.fullPath);
      }
      catch(IOException err) {
        return false;
      }
    }

    public boolean isFile() {
      try {
        return Files.isRegularFile(this.fullPath);
      }
      catch(IOException err) {
        return false;
      }
    }

    public long getFileSize() {
      if(!isFile()) return -1L;
      try {
        return Files.size(fullPath);
      }
      catch(IOException err) {
        return -1L;
      }
    }

    @Override
    public String getLabel() {
      return noDirName;
    }
    private String _fileExtension(String s) {
      if(s.endsWith(".gz")) {
        return _fileExtension(s.substring(0,s.length()-3));
      }
      else if(s.endsWith(".bz2")) {
        return _fileExtension(s.substring(0,s.length()-4));
      }
      int i= s.lastIndexOf(".");
      if(i<=0) return "";
      return s.substring(i+1);
    }

    String getFileType() {
      if(isDirectory()) return "directory";
      return _fileExtension(noDirName);
    }

    @Override
    void write(XMLStreamWriter w) {
      w.writeStartElement("node");

      w.writeAttribute("id",getId());
      w.writeAttribute("label",getLabel());
      w.writeStartElement("attvalues");
      w.writeEmptyElement("attvalue");
      w.writeAttribute("for",ATT_NODE_TYPE);
      w.writeAttribute("value","task");

      w.writeEmptyElement("attvalue");
      w.writeAttribute("for",ATT_FULLPATH);
      w.writeAttribute("value",fullPath.toString());


      final long file_size = getFileSize();
      if(file_size >=0L ) {
        w.writeEmptyElement("attvalue");
        w.writeAttribute("for",ATT_FILESIZE);
        w.writeAttribute("value",String.valueOf(file_size));
      }

      final String file_type = getFileType();
      if(!file_type.isEmpty()) {
        w.writeEmptyElement("attvalue");
        w.writeAttribute("for",ATT_FILETYPE);
        w.writeAttribute("value",file_type);
      }

      w.writeEndElement();//attvalues

      w.writeEmptyElement("viz","shape", VIZ_NS);
      w.writeAttribute("value","square");

      w.writeEmptyElement("viz","color", VIZ_NS);
      w.writeAttribute("hex","#CA3C66");

      if(this.viz_size>0) {
        w.writeEmptyElement("viz","size", VIZ_NS);
        w.writeAttribute("value",String.valueOf(this.viz_size));
      }

      w.writeEndElement();
    }

    @Override
    public int hashCode() {
      return fullPath.hashCode();
    }
  }

  private static class GexfGraph {

    private final Map<GexfNode,GexfNode> node2node = new HashMap<>();
    private final Set<GexfEdge> edges = new HashSet<>();
    GexfNode addNode(GexfNode n) {
      final GexfNode prev = node2node.get(n);
      if(prev!=null) return prev;
      node2node.put(n,n);
      return n;
    }
    GexfEdge addEdge(GexfNode source, GexfNode target) {
      final GexfEdge n = new GexfEdge(source,target);
      edges.add(n);
      return n;
    }

    void writeXml(final Path xmlFile) {
      long min_file_size = -1L;
      long max_file_size = 0L;

      for(GexfNode n: node2node.values()) {
        if(!(n instanceof FileGexfNode)) continue;
        final FileGexfNode fn = (FileGexfNode)n;
        final long file_size = fn.getFileSize();
        if(file_size < 0L ) continue;
        if(min_file_size==-1L || file_size < min_file_size) min_file_size = file_size;
        max_file_size = java.lang.Math.max(max_file_size, file_size );
      }

      if(min_file_size!=-1L && min_file_size < max_file_size) {
        for(GexfNode n: node2node.values()) {
          if(!(n instanceof FileGexfNode)) continue;
          final FileGexfNode fn = (FileGexfNode)n;
          final long file_size = fn.getFileSize();
          if(file_size < 0L ) continue;
          fn.viz_size = 10.0 + ((file_size-min_file_size)/(double)(max_file_size-min_file_size))*90;
        }
      }

      Files.newOutputStream(xmlFile).withCloseable {outputStream ->
        final XMLOutputFactory xof = XMLOutputFactory.newInstance();
        final String encoding="UTF-8";
        final XMLStreamWriter w = xof.createXMLStreamWriter(outputStream, encoding);

        w.writeStartDocument(encoding, "1.0");
        w.writeStartElement("gexf");
        w.writeAttribute("xmlns",  XMLNS);
        w.writeAttribute("xmlns:xsi",XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
        w.writeAttribute("xmlns:viz", VIZ_NS);
        w.writeAttribute("xsi:schemaLocation", SCHEMA_LOCATION);
        w.writeAttribute("version", VERSION);
        w.writeStartElement("meta");
        w.writeAttribute("lastmodifieddate",new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        w.writeStartElement("creator");
        w.writeCharacters("GEXF Renderer for NF-PROV by Pierre Lindenbaum");
        w.writeEndElement();

        w.writeStartElement("description");
        w.writeCharacters("GEXF Renderer for NF-PROV");
        w.writeEndElement();

        w.writeEndElement();//meta
        w.writeStartElement("graph");
        w.writeAttribute("mode", "static");
        w.writeAttribute("defaultedgetype", "directed");

        w.writeStartElement("attributes");
        w.writeAttribute("class", "node");
        w.writeAttribute("mode", "static");

        w.writeStartElement("attribute");
        w.writeAttribute("id", ATT_CACHED);
        w.writeAttribute("title", "cached");
        w.writeAttribute("type", "boolean");
        w.writeStartElement("default");
        w.writeCharacters("false");
        w.writeEndElement();
        w.writeEndElement();

        w.writeStartElement("attribute");
        w.writeAttribute("id", ATT_ABORTED);
        w.writeAttribute("title", "aborted");
        w.writeAttribute("type", "boolean");
        w.writeStartElement("default");
        w.writeCharacters("false");
        w.writeEndElement();
        w.writeEndElement();

        w.writeStartElement("attribute");
        w.writeAttribute("id", ATT_NODE_TYPE);
        w.writeAttribute("title", "node_type");
        w.writeAttribute("type", "string");
        w.writeStartElement("default");
        w.writeCharacters("file");
        w.writeEndElement();//default
        w.writeStartElement("options");
        w.writeCharacters("[file, task]");
        w.writeEndElement();//options
        w.writeEndElement();//attribute

        w.writeStartElement("attribute");
        w.writeAttribute("id", ATT_FULLPATH);
        w.writeAttribute("title", "path");
        w.writeAttribute("type", "string");
        w.writeStartElement("default");
        w.writeCharacters("");
        w.writeEndElement();
        w.writeEndElement();

        w.writeStartElement("attribute");
        w.writeAttribute("id", ATT_PROC_NAME);
        w.writeAttribute("title", "processus");
        w.writeAttribute("type", "string");
        w.writeStartElement("default");
        w.writeCharacters("");
        w.writeEndElement();
        w.writeEndElement();

        w.writeStartElement("attribute");
        w.writeAttribute("id", ATT_FILESIZE);
        w.writeAttribute("title", "file_size");
        w.writeAttribute("type", "float");
        w.writeStartElement("default");
        w.writeCharacters("-1.0");
        w.writeEndElement();
        w.writeEndElement();


        w.writeStartElement("attribute");
        w.writeAttribute("id", ATT_FILETYPE);
        w.writeAttribute("title", "file_suffix");
        w.writeAttribute("type", "string");
        w.writeStartElement("default");
        w.writeCharacters("");
        w.writeEndElement();
        w.writeEndElement();

        w.writeEndElement();//attributes

        w.writeStartElement("attributes");
        w.writeAttribute("class", "edge");
        w.writeAttribute("mode", "static");
        w.writeEndElement();//attributes

        w.writeStartElement("nodes");
        for(GexfNode n: this.node2node.values()) {
          n.write(w);
        }
        w.writeEndElement();//nodes

        w.writeStartElement("edges");

        for(GexfEdge e: this.edges) {
          e.write(w);
        }
        w.writeEndElement();//edges


        w.writeEndElement();//graph
        w.writeEndElement();//gexf
        w.writeEndDocument();
        w.flush();
      }
    }
  }
}