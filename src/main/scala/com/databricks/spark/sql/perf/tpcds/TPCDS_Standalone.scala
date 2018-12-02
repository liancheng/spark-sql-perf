package com.databricks.spark.sql.perf.tpcds

import com.typesafe.scalalogging.slf4j.{LazyLogging => Logging}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, LocalFileSystem, Path}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.SparkSession

import com.databricks.spark.sql.perf.RunBenchmark

case class TpcdsStandaloneConfig(
  useLocalMaster: Boolean = false,
  datasetLocation: String = null,
  outputLocation: String = null,
  scaleFactor: Int = 10,
  iterations: Int = 1,
  regenerateDataset: Boolean = false,
  useDecimal: Boolean = true,
  useDate: Boolean = true,
  filterNull: Boolean = false,
  shuffle: Boolean = true,
  timeoutHours: Int = 60,
  format: String = "parquet",
  shufflePartitions: Int = 200,
  partitionedTables: Boolean = true,
  baseline: Option[Long] = None,
  queries: Option[String] = None)

object TPCDS_Standalone extends Logging {

  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[TpcdsStandaloneConfig]("tpcds-standalone") {
      head("tpcds-standalone", "0.2.0")
      opt[Boolean]('l', "use_local_master")
        .action { (x, c) => c.copy(useLocalMaster = x) }
        .text("whether to use the local master")
      opt[String]('d', "dataset_location")
        .action { (x, c) => c.copy(datasetLocation = x) }
        .text("location where to store the datasets (HDFS)")
        .required()
      opt[String]('o', "output_location")
        .action { (x, c) => c.copy(outputLocation = x) }
        .text("location where to store the results")
        .required()
      opt[Int]('s', "scale_factor")
        .action { (x, c) => c.copy(scaleFactor = x) }
        .text("scale factor for the TPC-DS")
      opt[Int]('p', "shuffle_partitions")
        .action { (x, c) => c.copy(shufflePartitions = x) }
        .text("number of shuffle partitions")
      opt[Boolean]('P', "partitioned_tables")
        .action { (x, c) => c.copy(partitionedTables = x) }
        .text("use partitioned table or not")
      opt[Int]('i', "iterations")
        .action((x, c) => c.copy(iterations = x))
        .text("the number of iterations to run")
      opt[Boolean]('r', "regenerate_dataset")
        .action((x, c) => c.copy(regenerateDataset = x))
        .text("whether to regenerate the test dataset")
      opt[Long]('c', "compare")
        .action((x, c) => c.copy(baseline = Some(x)))
        .text("the timestamp of the baseline experiment to compare with")
      opt[String]('Q', "queries")
        .action((x, c) => c.copy(queries = Some(x)))
        .text("a comma-separated list of query names to run, e.g.: q1-v2.4,q72-v2.4")
      help("help")
        .text("prints this usage text")
    }

    parser.parse(args, TpcdsStandaloneConfig()) match {
      case Some(config) =>
        if (!config.partitionedTables && config.scaleFactor > 10) {
          println(
            "Table partitioning helps data skipping for larger scale factors but " +
              "creates too many tiny files when the scale factor is small. " +
              "Therefore, -P/--partitioned_tables is only allowed when -s/--scale_factor is <= 10."
          )
          System.exit(1)
        }

        run(config)
      case None =>
        System.exit(1)
    }
  }

  def run(conf: TpcdsStandaloneConfig): Unit = {
    val spark = if (conf.useLocalMaster) {
      SparkSession
        .builder()
        .master("local[*]")
        .appName(getClass.getName)
        .enableHiveSupport()
        .getOrCreate()
    } else {
      SparkSession
        .builder()
        .appName(getClass.getName)
        .enableHiveSupport()
        .getOrCreate()
    }

    logger.info(s"=== DATASET LOCATION: ${generateDatasetLocation(conf)}")
    logger.info(s"=== DB NAME: ${generateDbName(conf)}")

    if (conf.regenerateDataset || !checkDirExists(generateDatasetLocation(conf), new Configuration())) {
      logger.info("=== GENERATING TEST DATASET ===")
      generateDataset(spark, conf)
      logger.info("=== TEST DATASET GENERATION COMPLETE ===")
    } else {
      logger.info("=== TEST DATASET ALREADY GENERATED ===")
    }

    if (conf.regenerateDataset || !spark.catalog.databaseExists(generateDbName(conf))) {
      logger.info("=== GENERATING METADATA ===")
      generateMetadata(spark, conf)
      logger.info("=== TEST METADATA GENERATION COMPLETE ===")
    } else {
      logger.info("=== TEST METADATA ALREADY GENERATED ===")
    }

    logger.info("=== STARTING EXPERIMENT ===")
    spark.sql(s"use ${generateDbName(conf)}")
    executeExperiment(spark, conf)
    logger.info("=== EXPERIMENT COMPLETE ===")
  }

  /** Check if the file exists at the given path. */
  def checkDirExists(path: String, conf: Configuration): Boolean = {
    val hdpPath = new Path(path)
    val fs = getFileSystemForPath(hdpPath, conf)
    fs.exists(hdpPath)
  }

  def getFileSystemForPath(path: Path, conf: Configuration): FileSystem = {
    // For local file systems, return the raw local file system, such calls to flush()
    // actually flushes the stream.
    val fs = path.getFileSystem(conf)
    fs match {
      case localFs: LocalFileSystem => localFs.getRawFileSystem
      case _ => fs
    }
  }

  def executeExperiment(spark: SparkSession, conf: TpcdsStandaloneConfig): Unit = {
    spark.conf.set("spark.sql.shuffle.partitions", conf.shufflePartitions)

    val tpcds = new TPCDS(sqlContext = spark.sqlContext)

    val queriesToRun = conf.queries.map { list =>
      val queryNames = list.split(",").map { _.trim }.toSet
      tpcds.tpcds2_4Queries.filter { query => queryNames.contains(query.name) }
    }.getOrElse(tpcds.tpcds2_4Queries)

    if (conf.queries.isDefined) {
      logger.info(s"Running specified queries: ${queriesToRun.map(_.name).mkString(", ")}")
    } else {
      logger.info(s"Running all TPC-DS queries.")
    }

    val experiment = tpcds.runExperiment(
      queriesToRun,
      iterations = conf.iterations,
      resultLocation = conf.outputLocation,
      tags = Map(
        "runtype" -> "benchmark",
        "database" -> generateDbName(conf),
        "scale_factor" -> conf.scaleFactor.toString))

    logger.info(experiment.toString)
    experiment.waitForFinish(conf.timeoutHours * 60 * 60)

    import org.apache.spark.sql.functions.{col, substring}
    val summary = experiment.getCurrentResults
      .withColumn("Name", substring(col("name"), 2, 100))
      .withColumn("Runtime", (col("parsingTime") + col("analysisTime") + col("optimizationTime") + col("planningTime") + col("executionTime")) / 1000.0)
      .select("Name", "Runtime")
      .coalesce(1)

    summary.show(summary.count().toInt, truncate = false)

    RunBenchmark.presentFindings(spark.sqlContext, experiment)

    if (conf.baseline.isDefined) {
      RunBenchmark.compareResults(
        spark.sqlContext,
        conf.baseline.get,
        experiment.timestamp,
        conf.outputLocation,
        experiment.comparisonResultPath)
    }
  }

  def generateDatasetLocation(conf: TpcdsStandaloneConfig): String = {
    s"${conf.datasetLocation}/sf${conf.scaleFactor}-${conf.format}/" +
      s"useDecimal=${conf.useDecimal},useDate=${conf.useDate},filterNull=${conf.filterNull}"
  }

  def generateDbName(conf: TpcdsStandaloneConfig): String = {
    s"tpcds_sf${conf.scaleFactor}" +
      s"""_${if (conf.useDecimal) "with" else "no"}decimal""" +
      s"""_${if (conf.useDate) "with" else "no"}date""" +
      s"""_${if (conf.filterNull) "no" else "with"}nulls"""
  }

  def generateDataset(spark: SparkSession, conf: TpcdsStandaloneConfig): Unit = {
    // Limit the memory used by parquet writer
    SparkHadoopUtil.get.conf.set("parquet.memory.pool.ratio", "0.1")
    // TPCDS has around 2000 dates.
    spark.conf.set("spark.sql.shuffle.partitions", "2000")
    // Don't write too huge files.
    spark.sqlContext.setConf("spark.sql.files.maxRecordsPerFile", "20000000")

    val rootDir = generateDatasetLocation(conf)

    val tables = new TPCDSTables(
      spark.sqlContext,
      dsdgenDir = None,   // Use built-in dsdgen
      scaleFactor = conf.scaleFactor.toString,
      useDoubleForDecimal = !conf.useDecimal,
      useStringForDate = !conf.useDate)

    // generate all the small dimension tables
    val dsdgen_nonpartitioned = 10 // small tables do not need much parallelism in generation.
    val nonPartitionedTables = Array(
      "call_center",
      "catalog_page",
      "customer",
      "customer_address",
      "customer_demographics",
      "date_dim",
      "household_demographics",
      "income_band",
      "item",
      "promotion",
      "reason",
      "ship_mode",
      "store",
      "time_dim",
      "warehouse",
      "web_page",
      "web_site")

    nonPartitionedTables.foreach { t => {
      tables.genData(
          location = rootDir,
          format = conf.format,
          overwrite = true,   // overwrite the data that is already there
          partitionTables = conf.partitionedTables,
          clusterByPartitionColumns = conf.shuffle,
          filterOutNullPartitionValues = conf.filterNull,
          tableFilter = t,
          numPartitions = dsdgen_nonpartitioned)
    }}
    logger.info("Done generating non partitioned tables.")

    // leave the biggest/potentially hardest tables to be generated last.
    val dsdgen_partitioned = 10000 // recommended for SF10000+.
    val partitionedTables = Array(
      "inventory",
      "web_returns",
      "catalog_returns",
      "store_returns",
      "web_sales",
      "catalog_sales",
      "store_sales")

    partitionedTables.foreach { t => {
      tables.genData(
          location = rootDir,
          format = conf.format,
          overwrite = true,
          partitionTables = conf.partitionedTables,
          clusterByPartitionColumns = conf.shuffle,
          filterOutNullPartitionValues = conf.filterNull,
          tableFilter = t,
          numPartitions = dsdgen_partitioned)
    }}
    logger.info("Done generating partitioned tables.")
  }

  def generateMetadata(spark: SparkSession, conf: TpcdsStandaloneConfig): Unit = {
    val rootDir = generateDatasetLocation(conf)
    val databaseName = generateDbName(conf)

    val tables = new TPCDSTables(
      spark.sqlContext,
      dsdgenDir = None,   // Use built-in dsdgen
      scaleFactor = conf.scaleFactor.toString,
      useDoubleForDecimal = !conf.useDecimal,
      useStringForDate = !conf.useDate)

    spark.sql(s"drop database if exists $databaseName cascade")
    spark.sql(s"create database $databaseName")
    spark.sql(s"use $databaseName")

    logger.info("Generating external tables")
    tables.createExternalTables(
      rootDir,
      conf.format,
      databaseName,
      overwrite = true,
      discoverPartitions = conf.partitionedTables)
    logger.info("External table generation complete")

    // For CBO
    logger.info("Generating CBO statistics")
    tables.analyzeTables(databaseName, analyzeColumns = true)
    logger.info("Done generating CBO statistics")
  }
}
