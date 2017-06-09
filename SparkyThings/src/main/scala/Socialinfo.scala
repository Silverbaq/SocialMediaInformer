import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.graphx.GraphLoader
import org.apache.spark.sql.{SparkSession}


import scala.collection.mutable


/**
  * Created by silverbaq on 5/16/17.
  */

case class Profile(id: String, name: String, birthday: String, death: String,
                   children: Int, mother: String, father: String,
                   followers: List[String], following: List[String], pageRank: Double,
                   occupation: List[String], spouse: List[String],
                   wiki_content: String, imdb_content: String, images: List[String],
                   refs: List[String], wiki_url: String, tweets: List[Tweet])

case class WikiProfile(id: String, birthday: String, children: Int, death: String,
                       father: String, mother: String, name: String,
                       occupation: List[String], spouse: List[String],
                       content: String, images: List[String], refs: List[String],
                       url: String)

case class IMDBProfile(id: String, name: String, birthday: String, death: String,
                       children: Int, occupation: List[String], spouse: List[String],
                       content: String)

case class Tweet(id: String, date: String, message: String)

case class TwitterProfile(id: String, name: String, nickname: String, followers: List[String],
                          following: List[String], tweets: List[Tweet])


object Socialinfo {
                /* *** INSERT FILE PATH HERE *** */
  val baseInput = "file:/home/silverbaq/Documents/BJTU/Project2/twitter-stmo/"
  val baseInput2 = "file:/home/silverbaq/Documents/BJTU/Project2/wiki_imdb-stmo/"

  val relationFile = baseInput + "relations.txt"
  val twitterProfileInput = baseInput + "profiles.txt"
  val tweetsInput = baseInput + "tweets.txt"

  val imdbBasicInfoInput = baseInput2 + "imdb_basic_output.txt"
  val imdbAdditionInfoInput = baseInput2 + "imdb_content_output.txt"
  val wikiBasicInfoInput = baseInput2 + "wiki_basic_output.txt"
  val wikiAdditionInfoInput = baseInput2 + "wiki_addition_output.txt"


  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setAppName("Socialinfo")
      .setMaster("local[2]")
      .set("spark.executor.memory", "8g")
    val sc = new SparkContext(conf)


    // Read input into a graph and run pageRank
    val graph = GraphLoader.edgeListFile(sc, relationFile)
    val ranks = graph.pageRank(0.0001).vertices


    // Linking files together
    val spark = SparkSession.builder().appName("an example").getOrCreate()
    val basicWiki = spark.read.json(wikiBasicInfoInput)
    val additionalWiki = spark.read.json(wikiAdditionInfoInput)
    val basicImdb = spark.read.json(imdbBasicInfoInput)
    val imdbAddition = spark.read.json(imdbAdditionInfoInput)

    val twitterData = spark.read.json(twitterProfileInput).collect()
    val tweetsData = spark.read.json(tweetsInput).collect()
    val relationsData = sc.textFile(relationFile).map(_.split(" ")).collect()

    val wikiData = basicWiki.join(additionalWiki, Seq("id"))
    val wikiCollection = wikiData.collect()

    val imdbData = basicImdb.join(imdbAddition, Seq("id"))
    val imdbCollection = imdbData.collect()


    // Binding all the information
    val profiles = ranks.map(x => {

      var imdbProfile: IMDBProfile = null
      var wikiProfile: WikiProfile = null
      var twitterProfile: TwitterProfile = null


      // IMDB Profile
      val imdb = imdbCollection.filter(a => a(0).equals(x._1.toString))
      if (imdb.length > 0) {
        imdb.map(a => {
          imdbProfile = new IMDBProfile(
            id = a(0).toString,
            name = a(4).toString,
            birthday = a(1).toString,
            death = a(3).toString,
            children = a(2).toString.toInt,
            occupation = a(5).asInstanceOf[mutable.WrappedArray[String]].toList,
            spouse = a(6).asInstanceOf[mutable.WrappedArray[String]].toList,
            content = a(7).toString
          )
        })

      }

      // Wikipedia Profile
      val wiki = wikiCollection.filter(a => a(0).equals(x._1.toString))
      if (wiki.length > 0) {
        wiki.map(a => {
          wikiProfile = new WikiProfile(
            id = a(0).toString,
            birthday = a(1).toString,
            children = if (a(2).toString.equals("")) 0 else a(2).toString.toInt,
            death = a(3).toString,
            father = a(4).toString,
            mother = a(5).toString,
            name = a(6).toString,
            occupation = if (a(7) == null) null else a(7).asInstanceOf[mutable.WrappedArray[String]].toList,
            spouse = if (a(8) == null) null else a(8).asInstanceOf[mutable.WrappedArray[Any]].map(b => b.toString.split(",")(0).replace("<br />", "").replace("[", "").replace("]", "")).toList, // WrappedArray([Peggy Lentz<br />,1967,1974,end=divorced,marriage], [Mary Fisk<br />,1977,1987,end=divorced,marriage], [[[Mary Sweeney]]<br />,2006,2006,end=divorced,marriage], [Emily Stofle<br />,February 2009,null,null,marriage])
            content = a(9).toString,
            images = a(10).asInstanceOf[mutable.WrappedArray[String]].toList,
            refs = a(11).asInstanceOf[mutable.WrappedArray[String]].toList,
            url = a(12).toString
          )
          //println(a)
        })
        val test = ""
      }


      // Twitter Profile
      val twitter = twitterData.filter(a => a.getAs("id").equals(x._1.toString))
      if (twitter.length > 0) {
        val tweets = tweetsData.filter(a => a.getAs("twitterId").equals(x._1.toString)).map(a => new Tweet(a.getAs("id").toString, a.getAs("date").toString, a.getAs("message").toString)).toList
        val following = relationsData.filter(a => a.length > 1).filter(a => a(0).equals(x._1.toString)).flatMap(a => a).filter(a => !a.equals(x._1.toString)).toList
        val followers = relationsData.filter(a => a.length > 1).filter(a => a(1).equals(x._1.toString)).flatMap(a => a).filter(a => !a.equals(x._1.toString)).toList


        twitter.map(a => {
          twitterProfile = new TwitterProfile(
            id = a.getAs("id"),
            name = a.getAs("name"),
            nickname = a.getAs("nickname"),
            followers = followers,
            following = following,
            tweets = tweets
          )
        })
      }

      // Creating final profile and writting it to DB
      if (twitter.length > 0) {
        val p = createFinalProfile(imdbProfile, wikiProfile, twitterProfile, x._2)
        writeProfileToDatabase(p)
      }


    }).collect()


    sc.stop()
  }

  def writeProfileToDatabase(profile: Profile): Unit = {
    DBConnection.writeProfile(profile)
  }

  def createFinalProfile(imdb: IMDBProfile, wiki: WikiProfile, twitter: TwitterProfile, pageRank: Double): Profile = {
    var p: Profile = null

    if (imdb != null && wiki != null) {
      p = new Profile(id = twitter.id, name = twitter.name, birthday = checkString(wiki.birthday, imdb.birthday),
        death = checkString(wiki.death, imdb.death), children = checkNumber(wiki.children, imdb.children).toInt,
        mother = wiki.mother, father = wiki.mother, followers = twitter.followers, following = twitter.following,
        pageRank = pageRank, occupation = checkList(wiki.occupation, imdb.occupation),
        spouse = checkList(wiki.spouse, imdb.spouse), wiki_content = wiki.content, imdb_content = imdb.content,
        images = wiki.images, refs = wiki.refs, wiki_url = wiki.url, tweets = twitter.tweets)
    } else if (wiki == null && imdb != null) {
      p = new Profile(id = twitter.id, name = twitter.name, birthday = imdb.birthday,
        death = imdb.death, children = imdb.children,
        mother = "", father = "", followers = twitter.followers, following = twitter.following,
        pageRank = pageRank, occupation = imdb.occupation,
        spouse = imdb.spouse, wiki_content = "", imdb_content = imdb.content,
        images = List[String](), refs = List[String](), wiki_url = "", tweets = twitter.tweets)
    } else if (wiki != null && imdb == null) {
      p = new Profile(id = twitter.id, name = twitter.name, birthday = wiki.birthday,
        death = wiki.death, children = wiki.children,
        mother = wiki.mother, father = wiki.mother, followers = twitter.followers, following = twitter.following,
        pageRank = pageRank, occupation = wiki.occupation,
        spouse = wiki.spouse, wiki_content = wiki.content, imdb_content = "",
        images = wiki.images, refs = wiki.refs, wiki_url = wiki.url, tweets = twitter.tweets)
    } else {
      p = new Profile(id = twitter.id, name = twitter.name, birthday = "",
        death = "", children = 0,
        mother = "", father = "", followers = twitter.followers, following = twitter.following,
        pageRank = pageRank, occupation = List[String](),
        spouse = List[String](), wiki_content = "", imdb_content = "",
        images = List[String](), refs = List[String](), wiki_url = "", tweets = twitter.tweets)
    }
    return p
  }

  def checkString(current: String, x: String): String = x match {
    case "" => current
    case _ => x
  }

  def checkNumber(current: Double, x: Double): Double = {
    if (current > x) current
    else x
  }

  def checkList(current: List[String], x: List[String]): List[String] = current match {
    case null => x
    case _ => {
      val l = x.filter(a => !current.contains(a))
      l ::: current
    }
  }

}
