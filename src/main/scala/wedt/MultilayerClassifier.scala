package wedt

import java.io.{ByteArrayOutputStream, ObjectOutputStream}

import org.apache.spark.ml._
import org.apache.spark.ml.classification._
import org.apache.spark.ml.evaluation.{Evaluator, MulticlassClassificationEvaluator}
import org.apache.spark.ml.feature.{IndexToString, StringIndexer, StringIndexerModel}
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.tuning.{CrossValidator, CrossValidatorModel}
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import org.apache.spark.sql.types.{DoubleType, StructField, StructType}
import wedt.WEDT.{sparkContext, sqlContext}

class MultilayerClassifier[+M <: NaiveBayes](firstLevelOvrClassifier: M,
                                               secondLevelOvrClassifiers: List[M],
                                               _uid: String)
  extends Estimator[MultilayerClassificationModel] {

  import Implicits._

  override val uid: String = _uid

  override def transformSchema(schema: StructType): StructType = {
    require(schema.fieldNames.contains("features"), s"Column features does not exist.")
    schema
  }

  override def fit(dataset: Dataset[_]): MultilayerClassificationModel = {

    val df = dataset.asInstanceOf[Dataset[Row]]

    val firstLevelIndexer: StringIndexerModel = new StringIndexer()
      .setInputCol("firstLevelLabel")
      .setOutputCol("label")
      .fit(df)

    val firstLevelDataset = firstLevelIndexer.transform(df)

    val pm = firstLevelOvrClassifier.extractParamMap()

    firstLevelDataset.printSchema()

    println(s"firstLevelDataset has ${firstLevelDataset.count()} rows")

    firstLevelDataset
      .select("firstLevelLabel", "label", "features_3")
      .show(false)

    log.info(s"fitting 1 level using ${df.count} rows")
    val firstLevelClassifier = firstLevelOvrClassifier
        .fit(firstLevelDataset)

    val secondLevelClassifiers: Map[String, (StringIndexerModel, NaiveBayesModel)] = firstLevelIndexer.labels
      .zip(secondLevelOvrClassifiers)
      .map(e => {
        val subset = df.filter(f => f.getAs[String]("firstLevelLabel") == e._1)
        val indexer = new StringIndexer()
          .setInputCol("secondLevelLabel")
          .setOutputCol("label")
          .fit(subset)
        val indexedLabelsSubset = indexer.transform(subset.drop("label"))
        val classifier = e._2
        log.info("fitting 2nd level: " + e._1 + " using " + indexedLabelsSubset.count() + " rows")
        val cv = classifier
        e._1 -> (indexer, cv.fit(indexedLabelsSubset))
      }).toMap

    val globalIndexer: StringIndexerModel = new StringIndexer()
      .setInputCol("secondLevelLabel")
      .setOutputCol("label")
      .fit(df)

    new MultilayerClassificationModel(_uid + "_model", firstLevelClassifier, firstLevelIndexer, globalIndexer, secondLevelClassifiers)
  }

  override def copy(extra: ParamMap): Estimator[MultilayerClassificationModel] = ???
}