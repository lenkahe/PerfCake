/*
 * -----------------------------------------------------------------------\
 * PerfCake
 *  
 * Copyright (C) 2010 - 2016 the original author or authors.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -----------------------------------------------------------------------/
 */
package org.perfcake.reporting.destinations.c3chart;

import org.perfcake.PerfCakeException;
import org.perfcake.reporting.Measurement;
import org.perfcake.reporting.Quantity;
import org.perfcake.reporting.ReportingException;
import org.perfcake.util.Utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

import io.vertx.core.json.Json;

/**
 * @author <a href="mailto:marvenec@gmail.com">Martin Večeřa</a>
 */
public class C3ChartDataFile {

   /**
    * A logger for the class.
    */
   private static final Logger log = LogManager.getLogger(C3ChartDataFile.class);

   private static String[] resourceFiles = new String[] { "c3.min.css", "c3.min.js", "d3.v3.min.js", "report.css", "report.js", "favicon.svg" };

   /**
    * File counter for the stored combined chart.
    */
   private static int fileCounter = 1;

   /**
    * The JavaScript file representing chart data. This is not set for charts created as a combination of existing ones.
    */
   private transient File dataFile = null;

   /**
    * A file channel for storing results.
    */
   private transient FileChannel outputChannel;

   /**
    * Target path for storing all data files related to the chart. These are the data itself (.js), the description file (.dat),
    * and the quick view file (.html).
    */
   private final Path target;

   /**
    * This is set to false after a first result line is obtained from the getResultLine() method.
    * In the method, this is used to display a complete warning message for the user to be able to fix
    * the scenario. But we do not want the warning to show every time as it would slow down the performance.
    */
   private boolean firstResultsLine = true;

   private C3Chart chart;

   public C3ChartDataFile(final C3Chart chart, final Path target) throws PerfCakeException {
      this.chart = chart;
      this.target = target;

      createOutputFileStructure();

      writeDataHeader();
      writeQuickView();
      writeDescriptor();
   }

   public C3ChartDataFile(final File descriptorFile) throws PerfCakeException {
      try {
         final String chartJson = Utils.readFilteredContent(descriptorFile.toURI().toURL());
         chart = Json.decodeValue(chartJson, C3Chart.class);

         target = descriptorFile.getParentFile().getParentFile().toPath();
      } catch (IOException e) {
         throw new PerfCakeException("Unable to read chart descriptor: ", e);
      }
   }

   public C3Chart getChart() {
      return chart;
   }

   private File getDataFile() {
      if (dataFile == null) {
         final Path dataFilePath = Paths.get(target.toString(), "data", chart.getBaseName() + ".js");
         dataFile = dataFilePath.toFile();
      }

      return dataFile;
   }

   public void open() throws PerfCakeException {
      try {
         outputChannel = FileChannel.open(getDataFile().toPath(), StandardOpenOption.APPEND);
      } catch (final IOException e) {
         throw new PerfCakeException(String.format("Cannot open data file %s for appending data.", dataFile.getAbsolutePath()), e);
      }

   }

   /**
    * Gets a JavaScript line to be written to the data file that represents the current Measurement.
    * All attributes required by the attributes list of this chart must be present in the measurement for the line to be returned.
    *
    * @param measurement
    *       The current measurement.
    * @return The line representing the data in measurement specified by the attributes list of this chart, or null when there was some of
    * the attributes missing.
    */
   private String getResultLine(final Measurement measurement) {
      final StringBuilder sb = new StringBuilder();
      boolean missingAttributes = false;

      sb.append(chart.getBaseName());
      sb.append(".push([");
      switch (chart.getxAxisType()) {
         case TIME:
            sb.append(measurement.getTime());
            break;
         case ITERATION:
            sb.append(measurement.getIteration());
            break;
         case PERCENTAGE:
            sb.append(measurement.getPercentage());
            break;
      }

      for (final String attr : chart.getAttributes()) {
         if (chart.getAttributes().indexOf(attr) > 0) {
            sb.append(", ");

            // we do not have all required attributes, return an empty line
            if (!measurement.getAll().containsKey(attr)) {
               missingAttributes = true;
               if (firstResultsLine) {
                  log.warn(String.format("Missing attribute %s, skipping the record.", attr));
               }
            } else {
               final Object data = measurement.get(attr);
               if (data instanceof String) {
                  sb.append("'");
                  sb.append(((String) data).replaceAll("'", "\\'"));
                  sb.append("'");
               } else if (data instanceof Quantity) {
                  sb.append(((Quantity) data).getNumber().toString());
               } else {
                  sb.append(data == null ? "null" : data.toString());
               }
            }
         }
      }

      firstResultsLine = false;

      if (missingAttributes) { // we must postpone the return for all misses to be shown
         return "";
      }

      sb.append("]);\n");

      return sb.toString();
   }

   /**
    * Appends results to this chart based on the given Measurement.
    *
    * @param measurement
    *       The Measurement to be stored.
    * @throws ReportingException
    *       When it was not possible to write the data.
    */
   public void appendResult(final Measurement measurement) throws ReportingException {
      final String line = getResultLine(measurement);

      if (!"".equals(line)) {
         try {
            outputChannel.write(ByteBuffer.wrap(line.getBytes(Charset.forName(Utils.getDefaultEncoding()))));
         } catch (final IOException ioe) {
            throw new ReportingException(String.format("Could not append data to the chart file %s.", getDataFile().getAbsolutePath()), ioe);
         }
      }
   }

   public void close() throws PerfCakeException {
      try {
         outputChannel.close();
      } catch (final IOException e) {
         throw new PerfCakeException(String.format("Cannot close output channel to the file %s.", getDataFile().getAbsolutePath()), e);
      }
   }

   /**
    * Writes the initial header and array definition to the JavaScript data file.
    *
    * @throws PerfCakeException
    *       When it was not possible to write the data.
    */
   private void writeDataHeader() throws PerfCakeException {
      final StringBuilder dataHeader = new StringBuilder("var ");
      dataHeader.append(chart.getBaseName());
      dataHeader.append(" = [ [ ");

      boolean first = true;
      for (final String attr : chart.getAttributes()) {
         if (first) {
            dataHeader.append("'");
            first = false;
         } else {
            dataHeader.append(", '");
         }
         dataHeader.append(attr);
         dataHeader.append("'");
      }
      dataHeader.append(" ] ];\n");

      dataHeader.append("\n");
      Utils.writeFileContent(getDataFile(), dataHeader.toString());
   }

   /**
    * Writes a quick view HTML file that can display the chart during the test run.
    *
    * @throws PerfCakeException
    *       When it was not possible to store the quick view file.
    */
   private void writeQuickView() throws PerfCakeException {
      final Path quickViewFile = Paths.get(target.toString(), "data", chart.getBaseName() + ".html");
      final Properties quickViewProps = new Properties();
      quickViewProps.setProperty("baseName", chart.getBaseName());
      quickViewProps.setProperty("xAxis", chart.getxAxis());
      quickViewProps.setProperty("yAxis", chart.getyAxis());
      quickViewProps.setProperty("chartName", chart.getName());
      switch (chart.getxAxisType()) {
         case TIME:
            quickViewProps.setProperty("format", "ms2hms");
            break;
         case ITERATION:
            quickViewProps.setProperty("format", "function(x) { return x; }");
            break;
         case PERCENTAGE:
            quickViewProps.setProperty("format", "function(x) { return '' + x + '%'; }");
            break;
      }
      Utils.copyTemplateFromResource("/c3chart/quick-view.html", quickViewFile, quickViewProps);
   }

   private void writeDescriptor() throws PerfCakeException {
      final Path descriptorFile = Paths.get(target.toString(), "data", chart.getBaseName() + ".json");
      Utils.writeFileContent(descriptorFile, Json.encode(chart));
   }

   /**
    * Creates output files structure including all needed CSS and JS files.
    *
    * @throws PerfCakeException
    *       When it was not possible to create any of the directories or files.
    */
   private void createOutputFileStructure() throws PerfCakeException {
      if (!target.toFile().exists()) {
         if (!target.toFile().mkdirs()) {
            throw new PerfCakeException("Could not create output directory: " + target.toFile().getAbsolutePath());
         }
      } else {
         if (!target.toFile().isDirectory()) {
            throw new PerfCakeException("Could not create output directory. It already exists as a file: " + target.toFile().getAbsolutePath());
         }
      }

      File dir = Paths.get(target.toString(), "data").toFile();
      if (!dir.exists() && !dir.mkdirs()) {
         throw new PerfCakeException("Could not create data directory: " + dir.getAbsolutePath());
      }

      dir = Paths.get(target.toString(), "src").toFile();
      if (!dir.exists() && !dir.mkdirs()) {
         throw new PerfCakeException("Could not create source directory: " + dir.getAbsolutePath());
      }

      try {
         for (final String resourceFileName: resourceFiles) {
            copyResourceFile(resourceFileName);
         }
      } catch (final IOException e) {
         throw new PerfCakeException("Cannot copy necessary chart resources to the output path: ", e);
      }
   }

   private void copyResourceFile(final String resourceFileName) throws IOException {
      Files.copy(getClass().getResourceAsStream("/c3chart/" + resourceFileName), Paths.get(target.toString(), "src", resourceFileName), StandardCopyOption.REPLACE_EXISTING);
   }

}
