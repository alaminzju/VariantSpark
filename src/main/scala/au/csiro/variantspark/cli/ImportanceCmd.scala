package au.csiro.variantspark.cli

import au.csiro.sparkle.common.args4j.ArgsApp
import au.csiro.sparkle.cmd.CmdApp
import org.kohsuke.args4j.Option
import au.csiro.pbdava.ssparkle.common.arg4j.AppRunner
import au.csiro.pbdava.ssparkle.spark.SparkApp
import collection.JavaConverters._
import au.csiro.variantspark.input.VCFSource
import au.csiro.variantspark.input.VCFFeatureSource
import au.csiro.variantspark.input.HashingLabelSource
import au.csiro.variantspark.algo.WideRandomForest
import org.apache.spark.mllib.linalg.Vectors
import au.csiro.variantspark.input.CsvLabelSource
import au.csiro.variantspark.cmd.Echoable
import org.apache.spark.Logging
import org.apache.commons.lang3.builder.ToStringBuilder
import au.csiro.variantspark.cmd.EchoUtils._
import au.csiro.pbdava.ssparkle.common.utils.LoanUtils
import com.github.tototoshi.csv.CSVWriter
import au.csiro.pbdava.ssparkle.common.arg4j.TestArgs
import org.apache.hadoop.fs.FileSystem
import au.csiro.variantspark.algo.WideDecisionTree
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation
import au.csiro.pbdava.ssparkle.spark.SparkUtils
import au.csiro.pbdava.ssparkle.common.utils.ReusablePrintStream
import au.csiro.variantspark.algo.WideRandomForestCallback
import au.csiro.variantspark.utils.VectorRDDFunction._
import au.csiro.variantspark.input.CsvFeatureSource
import au.csiro.variantspark.algo.RandomForestParams
import au.csiro.variantspark.data.BoundedOrdinal

class ImportanceCmd extends ArgsApp with SparkApp with Echoable with Logging with TestArgs {

  @Option(name="-if", required=true, usage="This is input files", aliases=Array("--input-file"))
  val inputFile:String = null

  @Option(name="-it", required=false, usage="Input file type (vcf|csv) ", aliases=Array("--input-type"))
  val inputType:String = "vcf"

  @Option(name="-ff", required=true, usage="Features file", aliases=Array("--feature-file"))
  val featuresFile:String = null

  @Option(name="-fc", required=true, usage="Feature column", aliases=Array("--feature-column"))
  val featureColumn:String = null
  
  @Option(name="-nv", required=false, usage="Number od variables to print", aliases=Array("--n-variables"))
  val nVariables:Int = 20

  @Option(name="-t", required=false, usage="Number of tree to build", aliases=Array("--n-trees") )
  val nTrees:Int = 5

  @Option(name="-of", required=false, usage="Output file", aliases=Array("--output-file") )
  val outputFile:String = null

  @Option(name="-c", required=false, usage="Compute and display pair-wise correlation for important variables", aliases=Array("--correlation") )
  val computeCorrelation:Boolean = false

  @Option(name="-ct", required=false, usage="Only display pairs with abs(correlation) greater then the threshold (def=0.6)", aliases=Array("--correlation-threshold") )
  val correlationThreshold = 0.6
 
  @Option(name="-d", required=false, usage="Include important variables data"
        , aliases=Array("--include-data") )
  val includeData = false
 
  @Option(name="-rt", required=false, usage="RandomForest mTry" , aliases=Array("--rf-mtry"))
  val rfMTry:Long = -1L
  
  @Option(name="-rtf", required=false, usage="RandomForest mTry fraction" , aliases=Array("--rf-mtry-fraction"))
  val rfMTryFraction:Double = Double.NaN
  
  @Option(name="-ro", required=false, usage="RandomForest estimate oob" , aliases=Array("--rf-oob"))
  val rfEstimateOob:Boolean = false

  @Option(name="-rbs", required=false, usage="RandomForest batch size (no of trees to build at the same time)" 
      , aliases=Array("--rf-batch-size"))
  val rfBatchSize:Int = -1


  @Option(name="-vo", required=false, usage="Variable type ordinal with this number of levels (def = 3)" 
      , aliases=Array("--var-ordinal"))
  val varOrdinalLevels:Int = 3;

  // spark relareds
  @Option(name="-sp", required=false, usage="Spark parallelism", aliases=Array("--spark-par"))
  val sparkPar = 0
 
  @Override
  def testArgs = Array("-if", "data/small.vcf", "-ff", "data/small-labels.csv", "-fc", "22_16051249", "-d")
  
  def loadVCF() = {
    echo(s"Loading header from VCF file: ${inputFile}")
    val vcfSource = VCFSource(sc.textFile(inputFile, if (sparkPar > 0) sparkPar else sc.defaultParallelism))
    verbose(s"VCF Version: ${vcfSource.version}")
    verbose(s"VCF Header: ${vcfSource.header}")    
    VCFFeatureSource(vcfSource)    
  }
  
  def loadCSV() = {
    echo(s"Loading csv file: ${inputFile}")
    CsvFeatureSource(sc.textFile(inputFile, if (sparkPar > 0) sparkPar else sc.defaultParallelism))
  }
  
  @Override
  def run():Unit = {
    implicit val fs = FileSystem.get(sc.hadoopConfiguration)      
    logDebug(s"Runing with filesystem: ${fs}, home: ${fs.getHomeDirectory}")
    logInfo("Running with params: " + ToStringBuilder.reflectionToString(this))
    echo(s"Finding  ${nVariables}  most important features using random forest")

    val fileLoader = if ("csv".equals(inputType)) loadCSV _ else loadVCF _
    val source = fileLoader()
    
    echo(s"Loaded rows: ${dumpList(source.sampleNames)}")
     
    echo(s"Loading labels from: ${featuresFile}, column: ${featureColumn}")
    val labelSource = new CsvLabelSource(featuresFile, featureColumn)
    val labels = labelSource.getLabels(source.sampleNames)
    echo(s"Loaded labels: ${dumpList(labels.toList)}")
    
    echo(s"Loading features from: ${inputFile}")
    
    val inputData = source.features().map(_.toVector).zipWithIndex().cache()
    val totalVariables = inputData.count()
    val variablePerview = inputData.map({case (f,i) => f.label}).take(defaultPreviewSize).toList
    
    echo(s"Loaded variables: ${dumpListHead(variablePerview, totalVariables)}")    
    
    // discover variabele type
    // for now assume it's ordered factor with provided number of levels
    echo(s"Assumed original variable with ${varOrdinalLevels} levles")
    // TODO (Feature): Add autodiscovery
    val dataType = BoundedOrdinal(varOrdinalLevels)
    
    if (isVerbose) {
      verbose("Data preview:")
      source.features().take(defaultPreviewSize).foreach(f=> verbose(s"${f.label}:${dumpList(f.values.toList, longPreviewSize)}"))
    }
    
    echo(s"Training random forest - trees: ${nTrees}")  
    val startTime = System.currentTimeMillis()
    val rf = new WideRandomForest(RandomForestParams(oob=rfEstimateOob,
        nTryFraction = if (rfMTry > 0) rfMTry.toDouble/totalVariables else rfMTryFraction))
    val traningData = inputData.map{ case (f, i) => (f.values, i)}
    
    implicit val rfCallback = new WideRandomForestCallback() {
      override   def onParamsResolved(actualParams:RandomForestParams) {
        echo(s"RF Params: ${actualParams}")
        echo(s"RF Params mTry: ${(actualParams.nTryFraction * totalVariables).toLong}")
      }
      override  def onTreeComplete(treeIndex:Int, oobError:Double, elapsedTimeMs:Long) {
        echo(s"Finished tree: ${treeIndex}, current oobError: ${oobError}, time: ${elapsedTimeMs} ms")
      }
    }
    val result = if (rfBatchSize > 0) {
      echo(s"Running in batch mode with size (oob not available): ${rfBatchSize}")
      rf.batchTrain(traningData, dataType, labels, nTrees, rfBatchSize)
    } else {
      rf.train(traningData, dataType, labels, nTrees)  
    }
    
    val duration = System.currentTimeMillis() - startTime
    echo(s"Random forest oob accuracy: ${result.oobError}, took ${duration/1000.0} s") 

    // build index for names
    val topImportantVariables = result.variableImportance.toSeq.sortBy(-_._2).take(nVariables)
    val topImportantVariableIndexes = topImportantVariables.map(_._1).toSet
    
    val index = SparkUtils.withBrodcast(sc)(topImportantVariableIndexes) { br_indexes => 
      inputData.filter(t => br_indexes.value.contains(t._2)).map({case (f,i) => (i, f.label)}).collectAsMap()
    }
    
    val varImportance = topImportantVariables.map({ case (i, importance) => (index(i), importance)})
    
    if (isEcho && outputFile!=null) {
      echo("Variable importance preview")
      varImportance.take(math.min(nVariables, defaultPreviewSize)).foreach({case(label, importance) => echo(s"${label}: ${importance}")})
    }

      // TODO (Optimize): Only needed when data is reqruied
      // compute correlation
    val importntVariableData = traningData.collectAtIndexes(topImportantVariableIndexes) 
    
    if (computeCorrelation) {
      // compute correlation
      val cor = new PearsonsCorrelation()
      val cors = for (i <-importntVariableData.keys; j <-importntVariableData.keys if i !=j) yield ((index(i),index(j)), cor.correlation(importntVariableData(i).toArray
          , importntVariableData(j).toArray)) 
      println("correlation")
      cors.filter(t => Math.abs(t._2) > correlationThreshold).toList.sorted.foreach(println)  
    }
    
    LoanUtils.withCloseable(if (outputFile != null ) CSVWriter.open(outputFile) else CSVWriter.open(ReusablePrintStream.stdout)) { writer =>
      val header = List("variable","importance") ::: (if (includeData) source.sampleNames else Nil)
      writer.writeRow(header)
      writer.writeAll(topImportantVariables.map({case (i, importance) => 
        List(index(i), importance) ::: (if (includeData) importntVariableData(i).toArray.toList else Nil)}))
    }
  }
}

object ImportanceCmd  {
  def main(args:Array[String]) {
    AppRunner.mains[ImportanceCmd](args)
  }
}
