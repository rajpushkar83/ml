/**
 * Copyright (c) 2013, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */
package com.cloudera.science.ml.client.cmd;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.crunch.PCollection;
import org.apache.crunch.PTable;
import org.apache.crunch.Pair;
import org.apache.crunch.Pipeline;
import org.apache.crunch.PipelineResult;
import org.apache.crunch.lib.Sample;
import org.apache.crunch.types.avro.Avros;
import org.apache.hadoop.conf.Configuration;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.beust.jcommander.converters.CommaParameterSplitter;
import com.cloudera.science.ml.client.params.PipelineParameters;
import com.cloudera.science.ml.client.params.RecordInputParameters;
import com.cloudera.science.ml.client.params.RecordOutputParameters;
import com.cloudera.science.ml.core.records.Header;
import com.cloudera.science.ml.core.records.Record;
import com.cloudera.science.ml.core.records.Spec;
import com.cloudera.science.ml.core.records.Specs;
import com.cloudera.science.ml.parallel.records.Records;
import com.cloudera.science.ml.parallel.sample.RecordGroupFn;
import com.cloudera.science.ml.parallel.sample.ReservoirSampling;
import com.cloudera.science.ml.parallel.sample.WeightingFn;
import com.cloudera.science.ml.parallel.types.MLRecords;
import com.google.common.collect.Lists;

@Parameters(commandDescription = "Samples from a dataset and writes the sampled data to HDFS or a local file")
public class SampleCommand implements Command {

  @Parameter(names = "--output-path", required=true,
      description = "The location of the output samples on HDFS")
  private String outputPath;
  
  @Parameter(names = "--size",
      description = "Samples N records uniformly from the input (mutually exclusive with --prob)")
  private int sampleSize = 0;
  
  @Parameter(names = "--prob",
      description = "Sample each record in the input independently with the given probability (mutually exclusive with --size)")
  private double samplingProbability = 0.0;
  
  @Parameter(names = "--header-file",
      description = "The header file for the input records")
  private String headerFile;
  
  @Parameter(names = "--group-fields",
      splitter = CommaParameterSplitter.class,
      description = "Sample N records for each distinct combination of the CSV-separated input fields. Used with the --size option")
  private List<String> groupFields = Lists.newArrayList();
  
  @Parameter(names = "--weight-field",
      description = "Use the given field in the input to weight some samples higher than others. Used with the --size and option")
  private String weightField;
  
  @Parameter(names = "--weight-invert",
      description = "Invert the weight field, which will prefer records with a small value for this field instead of a large one")
  private boolean invert = false;
  
  @Parameter(names = "--weight-default",
      description = "The default value to use when the value of the weight field is <= 0")
  private double defaultWeight = 0.0;
  
  @ParametersDelegate
  private PipelineParameters pipelineParams = new PipelineParameters();
  
  @ParametersDelegate
  private RecordInputParameters inputParams = new RecordInputParameters();

  @ParametersDelegate
  private RecordOutputParameters outputParams = new RecordOutputParameters();
  
  @Override
  public int execute(Configuration conf) throws IOException {
    Pipeline p = pipelineParams.create(SampleCommand.class, conf);
    Header header = null;
    if (headerFile != null) {
      header = Header.fromFile(new File(headerFile));
    }
    Records elements = inputParams.getRecords(p, header);

    if (sampleSize > 0 && samplingProbability > 0.0) {
      throw new IllegalArgumentException("--size and --prob are mutually exclusive options.");
    }
    
    if (sampleSize > 0) {
      if (elements.getSpec() != null) {
        Spec spec = elements.getSpec();
        if (weightField != null && !Specs.isNumeric(spec, weightField)) {
          throw new CommandException("Non-numeric weight field: " + weightField);
        }
        PCollection<Pair<Record, Double>> weighted = elements.get().parallelDo("weights",
            new WeightingFn(spec, weightField, invert, defaultWeight),
            Avros.pairs(MLRecords.record(spec), Avros.doubles()));
        
        if (groupFields.isEmpty()) {
          outputParams.writeRecords(ReservoirSampling.weightedSample(weighted, sampleSize), spec, outputPath);
        } else {
          List<Integer> columnIds = Specs.getFieldIds(spec, groupFields);
          PTable<String, Pair<Record, Double>> grouped = weighted.by(new RecordGroupFn(columnIds), Avros.strings());
          PTable<String, Record> sample = ReservoirSampling.groupedWeightedSample(grouped, sampleSize);
          outputParams.writeRecords(sample.values(), spec, outputPath);
        }
      } else {
        outputParams.writeRecords(ReservoirSampling.sample(elements.get(), sampleSize), outputPath);  
      }
    } else if (samplingProbability > 0.0 && samplingProbability < 1.0) {
      outputParams.writeRecords(Sample.sample(elements.get(), samplingProbability), elements.getSpec(), outputPath);
    } else {
      throw new IllegalArgumentException(String.format(
          "Invalid input args: sample size = %d, sample prob = %.4f", 
          sampleSize, samplingProbability));
    }
    
    PipelineResult pr = p.done();
    return pr.succeeded() ? 0 : 1;
  }

  @Override
  public String getDescription() {
    return "Samples from a dataset and writes the sampled data to HDFS";
  }
}
