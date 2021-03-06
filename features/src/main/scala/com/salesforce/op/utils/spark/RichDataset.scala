/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.utils.spark

import com.salesforce.op.features.types._
import com.salesforce.op.features.{FeatureLike, FeatureSparkTypes, OPFeature}
import com.salesforce.op.utils.text.TextUtils
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{DataType, Metadata, MetadataBuilder, StructType}
import com.databricks.spark.avro._
import org.apache.spark.ml.linalg.{Vector, Vectors}
import scala.collection.mutable.{WrappedArray => MWrappedArray}

import scala.reflect.ClassTag


/**
 * Dataset enrichment functions
 */
object RichDataset {

  import com.salesforce.op.utils.spark.RichRow._
  import com.salesforce.op.utils.spark.RichDataType._

  private[op] val vectorToArrayUDF = udf { (v: Vector) => if (v == null) null else v.toArray }
  private[op] val arrayToVectorUDF = udf { (a: MWrappedArray[Double]) =>
    if (a == null) null else Vectors.dense(a.toArray[Double]).compressed
  }
  private[op] val IsVectorMetadataKey = "isVector"
  private[op] val OriginalNameMetaKey = "originalName"
  private[op] def schemaPath(path: String): String = s"${path.stripSuffix("/")}/schema"
  private[op] def dataPath(path: String): String = s"${path.stripSuffix("/")}/data"

  /**
   * Loads a dataframe from a saved Avro file and dataframe schema file generated by RichDataFrame.saveAvro.
   * Relies on spark-avro package for Avro file generation, which seems to have a bug/feature that makes all fields
   * nullable when you read them back.
   *
   * @param path data path
   * @return reconstructed dataframe (with all fields marked as nullable)
   */
  def loadAvro(path: String)(implicit spark: SparkSession): DataFrame = {
    val schemaStr = spark.sparkContext.textFile(schemaPath(path)).collect().mkString
    val schema = DataType.fromJson(schemaStr).asInstanceOf[StructType]
    val origNames = schema.fields.map(_.metadata.getString(OriginalNameMetaKey))
    val data = spark.read.avro(dataPath(path)).toDF(origNames: _*)
    val columns =
      for {
        (c, f) <- data.columns.zip(schema.fields)
        meta = f.metadata
        isVector = meta.contains(IsVectorMetadataKey) && meta.getBoolean(IsVectorMetadataKey)
        column = if (isVector) arrayToVectorUDF(col(c)) else col(c)
      } yield column.as(c, meta)

    data.select(columns: _*)
  }


  /**
   * A dataframe with three quantifiers: forall, exists, and forNone (see below)
   * the rest of extended functionality comes from RichDataset
   * @param ds data frame
   */
  implicit class RichDataFrame(ds: DataFrame) extends RichDataset(ds) {

    /**
     * Given a column name and a predicate, checks that all values satisfy the predicate
     *
     * @param columnName column name
     * @param predicate  predicate, T => Boolean
     * @tparam T column value type
     * @return true iff all values satisfy the predicate
     *
     *         Examples of usage:
     *         {{{
     *            myDF allOf "MyNumericColumn" should beBetween(-1, 1)
     *
     *            myDF someOf "MyStringColumn" should (
     *              (x: String) => (x contains "Country") || (x contains "State")
     *           )
     *         }}}
     */
    def forall[T](columnName: String)(predicate: T => Boolean): Boolean =
      forNone(columnName)((t: T) => !predicate(t))

    /**
     * Given a feature and a predicate, checks that all values satisfy the predicate
     *
     * @param feature feature that describes column
     * @tparam T column value type
     * @return a quantifier that acts on predicate, T => Boolean,
     *         producing true iff all values satisfy the predicate
     */
    def forall[T <: FeatureType: FeatureTypeSparkConverter]
      (feature: FeatureLike[T])(predicate: T#Value => Boolean): Boolean =
      forNone(feature)((t: T#Value) => !predicate(t))

    /**
     * Given a column name and a predicate, checks that some values satisfy the predicate
     *
     * @param columnName column name
     * @param predicate predicate, T => Boolean
     * @tparam T column value type
     * @return true iff at least one value satisfies the predicate
     */
    def exists[T](columnName: String)(predicate: T => Boolean): Boolean =
      !forNone(columnName)(predicate)

    /**
     * Given a feature and a predicate, checks that some values satisfy the predicate
     *
     * @param feature feature that describes column
     * @tparam T column value type
     * @return a quantifier that acts on predicate, T => Boolean,
     *         producing true iff at least one value satisfies the predicate
     */
    def exists[T <: FeatureType: FeatureTypeSparkConverter]
      (feature: FeatureLike[T])(predicate: T#Value => Boolean): Boolean =
      !forNone(feature)(predicate)

    /**
     * Given a column name and a predicate, checks that none of the values satisfy the predicate
     *
     * @param columnName column name
     * @param predicate predicate, T => Boolean
     * @tparam T column value type
     * @return true iff none of the values satisfy the predicate
     */
    def forNone[T](columnName: String)(predicate: T => Boolean): Boolean =
      ds.filter(row => predicate(row.getAs[T](columnName))).isEmpty

    /**
     * Given a feature and a predicate, checks that none of the values satisfy the predicate
     *
     * @param feature feature that describes column
     * @tparam T column value type
     * @return a quantifier that acts on predicate, T => Boolean,
     *         producing true iff none of the values satisfy the predicate
     */
    def forNone[T <: FeatureType : FeatureTypeSparkConverter]
      (feature: FeatureLike[T])(predicate: T#Value => Boolean): Boolean =
      ds.filter(row => predicate(row.getFeatureType(feature).value)).isEmpty
  }

  implicit class RichDataset(val ds: Dataset[_]) {

    /**
     * Will convert data frame with complex feature names and vector types into avro compatible format
     * and save it to the specified location
     *
     * @param path       location to save data
     * @param cleanNames should clean column names from non alphanumeric characters before saving
     * @param options    output options for the underlying data source
     * @param saveMode   Specifies the behavior when data or table already exists.
     *                   Options include:
     *                   - `SaveMode.Overwrite`: overwrite the existing data.
     *                   - `SaveMode.Append`: append the data.
     *                   - `SaveMode.Ignore`: ignore the operation (i.e. no-op).
     *                   - `SaveMode.ErrorIfExists`: default option, throw an exception at runtime.
     * @param spark      spark session used to save the original schema information with metadata
     */
    def saveAvro(
      path: String,
      cleanNames: Boolean = true,
      options: Map[String, String] = Map.empty,
      saveMode: SaveMode = SaveMode.ErrorIfExists
    )(implicit spark: SparkSession): Unit = {
      val schema = ds.schema
      val columns = ds.columns.map { c =>
        val cSchema = schema(c)
        val isVector = cSchema.dataType.equalsIgnoreNullability(FeatureSparkTypes.OPVector)
        val newMeta = {
          new MetadataBuilder()
            .withMetadata(cSchema.metadata)
            .putString(key = OriginalNameMetaKey, value = c)
            .putBoolean(key = IsVectorMetadataKey, value = isVector)
        }
        // TODO: Make an option to use a custom Avro type (record) to store sparse vectors directly
        val column = if (isVector) vectorToArrayUDF(col(c)) else col(c)
        val newName = if (cleanNames) TextUtils.cleanString(c) else c
        column.as(newName, newMeta.build)
      }
      val cleaned = ds.select(columns: _*)

      spark.sparkContext.parallelize(Seq(cleaned.schema.prettyJson), 1).saveAsTextFile(schemaPath(path))
      cleaned.write.mode(saveMode).options(options).avro(dataPath(path))
    }

    /**
     * Collects features from the dataset.
     *
     * Running collect requires moving all the data into the application's driver process, and
     * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
     *
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return array of feature values
     */
    def collect[F1 <: FeatureType : FeatureTypeSparkConverter : ClassTag](
      f: FeatureLike[F1]
    ): Array[F1] =
      select(f).collect().map(r => r.getFeatureType(f))

    /**
     * Collects features from the dataset.
     *
     * Running collect requires moving all the data into the application's driver process, and
     * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
     *
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return array of feature values
     */
    def collect[F1 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F2 <: FeatureType : FeatureTypeSparkConverter : ClassTag](
      f1: FeatureLike[F1], f2: FeatureLike[F2]
    ): Array[(F1, F2)] =
      select(f1, f2).collect().map(r => (r.getFeatureType(f1), r.getFeatureType(f2)))

    /**
     * Collects features from the dataset.
     *
     * Running collect requires moving all the data into the application's driver process, and
     * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
     *
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return array of feature values
     */
    def collect[F1 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F2 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F3 <: FeatureType : FeatureTypeSparkConverter : ClassTag](
      f1: FeatureLike[F1], f2: FeatureLike[F2], f3: FeatureLike[F3]
    ): Array[(F1, F2, F3)] =
      select(f1, f2, f3).collect().map(r => (r.getFeatureType(f1), r.getFeatureType(f2), r.getFeatureType(f3)))

    /**
     * Collects features from the dataset.
     *
     * Running collect requires moving all the data into the application's driver process, and
     * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
     *
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return array of feature values
     */
    def collect[F1 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F2 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F3 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F4 <: FeatureType : FeatureTypeSparkConverter : ClassTag](
      f1: FeatureLike[F1], f2: FeatureLike[F2], f3: FeatureLike[F3], f4: FeatureLike[F4]
    ): Array[(F1, F2, F3, F4)] =
      select(f1, f2, f3, f4).collect().map(r =>
        (r.getFeatureType(f1), r.getFeatureType(f2), r.getFeatureType(f3), r.getFeatureType(f4)))

    /**
     * Collects features from the dataset.
     *
     * Running collect requires moving all the data into the application's driver process, and
     * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
     *
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return array of feature values
     */
    def collect[F1 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F2 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F3 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F4 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F5 <: FeatureType : FeatureTypeSparkConverter : ClassTag](
      f1: FeatureLike[F1], f2: FeatureLike[F2], f3: FeatureLike[F3], f4: FeatureLike[F4], f5: FeatureLike[F5]
    ): Array[(F1, F2, F3, F4, F5)] =
      select(f1, f2, f3, f4, f5).collect().map(r =>
        (r.getFeatureType(f1), r.getFeatureType(f2), r.getFeatureType(f3),
          r.getFeatureType(f4), r.getFeatureType(f5)))

    /**
     * Collects features from the dataset and returns the first `n` values.
     *
     * Running collect requires moving all the data into the application's driver process, and
     * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
     *
     * @param n number of values to return
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return array of the first `n` feature values
     */
    def take[F1 <: FeatureType : FeatureTypeSparkConverter : ClassTag](
      n: Int, f: FeatureLike[F1]
    ): Array[F1] =
      select(f).take(n).map(r => r.getFeatureType(f))

    /**
     * Collects features from the dataset and returns the first `n` values.
     *
     * Running collect requires moving all the data into the application's driver process, and
     * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
     *
     * @param n number of values to return
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return array of the first `n` feature values
     */
    def take[F1 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F2 <: FeatureType : FeatureTypeSparkConverter : ClassTag](
      n: Int, f1: FeatureLike[F1], f2: FeatureLike[F2]
    ): Array[(F1, F2)] =
      select(f1, f2).take(n).map(r => (r.getFeatureType(f1), r.getFeatureType(f2)))

    /**
     * Collects features from the dataset and returns the first `n` values.
     *
     * Running collect requires moving all the data into the application's driver process, and
     * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
     *
     * @param n number of values to return
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return array of the first `n` feature values
     */
    def take[F1 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F2 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F3 <: FeatureType : FeatureTypeSparkConverter : ClassTag](
      n: Int, f1: FeatureLike[F1], f2: FeatureLike[F2], f3: FeatureLike[F3]
    ): Array[(F1, F2, F3)] =
      select(f1, f2, f3).take(n).map(r => (r.getFeatureType(f1), r.getFeatureType(f2), r.getFeatureType(f3)))

    /**
     * Collects features from the dataset and returns the first `n` values.
     *
     * Running collect requires moving all the data into the application's driver process, and
     * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
     *
     * @param n number of values to return
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return array of the first `n` feature values
     */
    def take[F1 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F2 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F3 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F4 <: FeatureType : FeatureTypeSparkConverter : ClassTag](
      n: Int, f1: FeatureLike[F1], f2: FeatureLike[F2],
      f3: FeatureLike[F3], f4: FeatureLike[F4]
    ): Array[(F1, F2, F3, F4)] =
      select(f1, f2, f3, f4).take(n).map(r => (r.getFeatureType(f1), r.getFeatureType(f2),
        r.getFeatureType(f3), r.getFeatureType(f4)))

    /**
     * Collects features from the dataset and returns the first `n` values.
     *
     * Running collect requires moving all the data into the application's driver process, and
     * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
     *
     * @param n number of values to return
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return array of the first `n` feature values
     */
    def take[F1 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F2 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F3 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F4 <: FeatureType : FeatureTypeSparkConverter : ClassTag,
    F5 <: FeatureType : FeatureTypeSparkConverter : ClassTag](
      n: Int, f1: FeatureLike[F1], f2: FeatureLike[F2],
      f3: FeatureLike[F3], f4: FeatureLike[F4], f5: FeatureLike[F5]
    ): Array[(F1, F2, F3, F4, F5)] =
      select(f1, f2, f3, f4, f5).take(n).map(r => (r.getFeatureType(f1), r.getFeatureType(f2),
        r.getFeatureType(f3), r.getFeatureType(f4), r.getFeatureType(f5)))

    /**
     * Selects features from the dataset.
     *
     * @param features features to select
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return a dataset containing the selected features
     */
    def select(features: OPFeature*): DataFrame = {
      requireValidSchema(ds.schema, features)
      ds.select(features.map(FeatureSparkTypes.toColumn): _*)
    }

    /**
     * Returns metadata map for features
     *
     * @param features features to get metadata for
     * @throws IllegalArgumentException if dataset schema does not match the features
     * @return metadata map for features
     */
    def metadata(features: OPFeature*): Map[OPFeature, Metadata] = {
      val schema = ds.schema
      requireValidSchema(schema, features)

      val fields = schema.fields.map(f => f.name -> f).toMap

      features.foldLeft(Map.empty[OPFeature, Metadata])((acc, feature) =>
        fields.get(feature.name).map(field => acc + (feature -> field.metadata)).getOrElse(acc)
      )
    }

    /**
     * Check if dataset is empty
     *
     * @return true if dataset is empty, false otherwise
     */
    def isEmpty: Boolean = ds.head(1).isEmpty

    /**
     * Validate dataset schema against the specified features
     *
     * @param schema   dataset schema
     * @param features features to validate against
     * @throws IllegalArgumentException if dataset schema does not match the features
     */
    private def requireValidSchema(schema: StructType, features: Seq[OPFeature]): Unit = {
      val validationResults = FeatureSparkTypes.validateSchema(schema, features)
      require(validationResults.isEmpty,
        "Dataset schema does not match the features. Errors: " + validationResults.mkString("'", "','", "'")
      )
    }

  }

}
