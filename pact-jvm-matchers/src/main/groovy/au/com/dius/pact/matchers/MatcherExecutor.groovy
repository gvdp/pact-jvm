package au.com.dius.pact.matchers

import au.com.dius.pact.model.matchingrules.Category
import au.com.dius.pact.model.matchingrules.DateMatcher
import au.com.dius.pact.model.matchingrules.MatchingRule
import au.com.dius.pact.model.matchingrules.NumberTypeMatcher
import au.com.dius.pact.model.matchingrules.RegexMatcher
import au.com.dius.pact.model.matchingrules.RuleLogic
import au.com.dius.pact.model.matchingrules.TimeMatcher
import au.com.dius.pact.model.matchingrules.TimestampMatcher
import au.com.dius.pact.model.matchingrules.TypeMatcher
import groovy.util.logging.Slf4j
import org.apache.commons.lang3.time.DateUtils
import scala.collection.Seq
import scala.xml.Elem

import java.text.ParseException

/**
 * Executor for matchers
 */
@Slf4j
class MatcherExecutor {
  static <Mismatch> List<Mismatch> domatch(Category matchers, Seq<String> path, def expected, def actual,
                                           MismatchFactory<Mismatch> mismatchFn) {
    def result = matchers.allMatchingRules().collect { matchingRule ->
      domatch(matchingRule, path, expected, actual, mismatchFn)
    }

    if (matchers.ruleLogic == RuleLogic.AND) {
      result.flatten() as List<Mismatch>
    } else {
      if (result.any { it.empty }) {
        []
      } else {
        result.flatten() as List<Mismatch>
      }
    }
  }

  static String safeToString(def value) {
    if (value == null) {
      ''
    } else if (value instanceof Elem) {
      value.text
    } else {
      value as String
    }
  }

  static String valueOf(def value) {
    if (value == null) {
      'null'
    } else if (value instanceof String) {
      "'$value'"
    } else {
      value as String
    }
  }

  static <Mismatch> List<Mismatch> domatch(MatchingRule matcher, Seq<String> path, def expected, def actual,
                                           MismatchFactory<Mismatch> mismatchFn) {
    if (matcher instanceof RegexMatcher) {
      matchRegex(matcher.regex, path, expected, actual, mismatchFn)
    } else if (matcher instanceof TypeMatcher) {
      matchType(path, expected, actual, mismatchFn)
    } else if (matcher instanceof NumberTypeMatcher) {
      matchNumber(matcher.numberType, path, expected, actual, mismatchFn)
    } else if (matcher instanceof DateMatcher) {
      matchDate(matcher.format, path, expected, actual, mismatchFn)
    } else if (matcher instanceof TimeMatcher) {
      matchTime(matcher.format, path, expected, actual, mismatchFn)
    } else if (matcher instanceof TimestampMatcher) {
      matchTimestamp(matcher.format, path, expected, actual, mismatchFn)
    } else {
      matchEquality(path, expected, actual, mismatchFn)
    }
  }

  static <Mismatch> List<Mismatch> matchEquality(Seq<String> path, Object expected, Object actual,
                                                 MismatchFactory<Mismatch> mismatchFactory) {
    def matches = safeToString(actual) == expected
    log.debug("comparing ${valueOf(actual)} to ${valueOf(expected)} at $path -> $matches")
    if (matches) {
      []
    } else {
      [ mismatchFactory.create(expected, actual, "Expected ${valueOf(actual)} to equal ${valueOf(actual)}", path) ]
    }
  }

  static <Mismatch> List<Mismatch> matchRegex(String regex, Seq<String> path, Object expected, Object actual,
                                              MismatchFactory<Mismatch> mismatchFactory) {
    def matches = safeToString(actual).matches(regex)
    log.debug("comparing ${valueOf(actual)} with regexp $regex at $path -> $matches")
    if (matches) {
      []
    } else {
      [ mismatchFactory.create(expected, actual, "Expected ${valueOf(actual)} to match '$regex", path) ]
    }
  }

  static <Mismatch> List<Mismatch> matchType(Seq<String> path, Object expected, Object actual,
                                             MismatchFactory<Mismatch> mismatchFactory) {
    log.debug("comparing type of ${valueOf(actual)} to ${valueOf(expected)} at $path")
    if (expected instanceof String && actual instanceof String
      || expected instanceof Number && actual instanceof Number
      || expected instanceof Boolean && actual instanceof Boolean
      || expected instanceof List && actual instanceof List
      || expected instanceof Map && actual instanceof Map
      || expected instanceof Elem && actual instanceof Elem && actual.label == expected.label) {
      []
    } else if (expected == null) {
      if (actual == null) {
        []
      } else {
        [ mismatchFactory.create(expected, actual, "Expected ${valueOf(actual)} to be null", path) ]
      }
    } else {
      [ mismatchFactory.create(expected, actual,
        "Expected ${valueOf(actual)} to be the same type as ${valueOf(expected)}", path) ]
    }
  }

  static def <Mismatch, Mismatch> List<Mismatch> matchNumber(NumberTypeMatcher.NumberType numberType, Seq<String> path,
                                                             def expected, def actual,
                                                             MismatchFactory<Mismatch> mismatchFactory) {
    if (expected == null && actual != null) {
      return [ mismatchFactory.create(expected, actual, "Expected ${valueOf(actual)} to be null", path) ]
    }
    switch (numberType) {
      case NumberTypeMatcher.NumberType.NUMBER:
        log.debug("comparing type of ${valueOf(actual)} to a number at $path")
        if (!(actual instanceof Number)) {
          return [ mismatchFactory.create(expected, actual, "Expected ${valueOf(actual)} to be a number", path) ]
        }
        break
      case NumberTypeMatcher.NumberType.INTEGER:
        log.debug("comparing type of ${valueOf(actual)} to an integer at $path")
        if (!(actual instanceof Integer) && !(actual instanceof Long) && !(actual instanceof BigInteger)) {
          return [ mismatchFactory.create(expected, actual, "Expected ${valueOf(actual)} to be an integer", path) ]
        }
        break
      case NumberTypeMatcher.NumberType.DECIMAL:
        log.debug("comparing type of ${valueOf(actual)} to a decimal at $path")
        if (!(actual instanceof Float) && !(actual instanceof Double) && !(actual instanceof BigDecimal)) {
          return [ mismatchFactory.create(expected, actual, "Expected ${valueOf(actual)} to be a decimal number",
            path) ]
        }
        break
    }
    []
  }

  static <Mismatch> List<Mismatch> matchDate(String format, Seq<String> path, Object expected, Object actual,
                                             MismatchFactory<Mismatch> mismatchFactory) {
    def pattern = format ?: 'yyyy-MM-dd'
    log.debug("comparing ${valueOf(actual)} to date pattern $pattern at $path")
    try {
      DateUtils.parseDate(safeToString(actual), pattern)
      []
    } catch (ParseException e) {
      [ mismatchFactory.create(expected, actual, "Expected ${valueOf(actual)} to match a date of '$pattern': " +
        "${e.message}", path) ]
    }
  }

  static <Mismatch> List<Mismatch> matchTime(String format, Seq<String> path, Object expected, Object actual,
                                             MismatchFactory<Mismatch> mismatchFactory) {
    def pattern = format ?: 'HH:mm:ss'
    log.debug("comparing ${valueOf(actual)} to time pattern $pattern at $path")
    try {
      DateUtils.parseDate(safeToString(actual), pattern)
      []
    } catch (ParseException e) {
      [ mismatchFactory.create(expected, actual, "Expected ${valueOf(actual)} to match a time of '$pattern': " +
        "${e.message}", path) ]
    }
  }

  static <Mismatch> List<Mismatch> matchTimestamp(String format, Seq<String> path, Object expected, Object actual,
                                             MismatchFactory<Mismatch> mismatchFactory) {
    def pattern = format ?: 'yyyy-MM-dd HH:mm:ssZZZ'
    log.debug("comparing ${valueOf(actual)} to timestamp pattern $pattern at $path")
    try {
      DateUtils.parseDate(safeToString(actual), pattern)
      []
    } catch (ParseException e) {
      [ mismatchFactory.create(expected, actual, "Expected ${valueOf(actual)} to match a timestamp of '$pattern': " +
        "${e.message}", path) ]
    }
  }

//  def matchTimestamp[Mismatch](path: Seq[String], expected: Any, actual: Any,
// mismatchFn: MismatchFactory[Mismatch]) = {
//    logger.debug(s"comparing ${valueOf(actual)} as Timestamp at $path")
//    try {
//      DateUtils.parseDate(Matchers.safeToString(actual), DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.getPattern,
//        DateFormatUtils.ISO_DATETIME_FORMAT.getPattern, DateFormatUtils.SMTP_DATETIME_FORMAT.getPattern,
//        "yyyy-MM-dd HH:mm:ssZZ", "yyyy-MM-dd HH:mm:ss"
//      )
//      List[Mismatch]()
//    }
//    catch {
//      case e: java.text.ParseException =>
//        logger.warn(s"failed to parse timestamp value of ${valueOf(actual)}", e)
//        List(mismatchFn.create(expected, actual, s"Expected ${valueOf(actual)} to be a timestamp", path))
//    }
//  }
//
//  def matchArray[Mismatch](path: Seq[String], expected: Any, actual: Any, mismatchFn: MismatchFactory[Mismatch],
// matcher: String, args: List[String]) = {
//    matcher match {
//      case "atleast" => actual match {
//        case v: List[Any] =>
//          if (v.asInstanceOf[List[Any]].size < args.head.toInt) List(mismatchFn.create(expected, actual,
// s"Expected ${valueOf(actual)} to have at least ${args.head} elements", path))
//          else List[Mismatch]()
//        case _ => List(mismatchFn.create(expected, actual, s"Array matcher $matcher can only be applied to arrays",
// path))
//      }
//      case _ => List(mismatchFn.create(expected, actual, s"Array matcher $matcher is not defined", path))
//    }
//  }
//
//object MinimumMatcher extends Matcher with StrictLogging {
//  def domatch[Mismatch](matcherDef: Map[String, Any], path: Seq[String], expected: Any, actual: Any,
// mismatchFn: MismatchFactory[Mismatch]): List[Mismatch] = {
//    val value = matcherDef("min") match {
//      case i: Int => i
//      case j: Integer => j.toInt
//      case o => o.toString.toInt
//    }
//    logger.debug(s"comparing ${valueOf(actual)} with minimum $value at $path")
//    actual match {
//      case v: List[Any] =>
//        if (v.size < value) {
//          List(mismatchFn.create(expected, actual, s"Expected ${valueOf(actual)} to have minimum $value", path))
//        } else {
//          List()
//        }
//      case v: Elem =>
//        if (v.child.size < value) {
//          List(mismatchFn.create(expected, actual, s"Expected ${valueOf(actual)} to have minimum $value", path))
//        } else {
//          List()
//        }
//      case _ => TypeMatcher.domatch[Mismatch](matcherDef, path, expected, actual, mismatchFn)
//    }
//  }
//}
//
//object MaximumMatcher extends Matcher with StrictLogging {
//  def domatch[Mismatch](matcherDef: Map[String, Any], path: Seq[String], expected: Any, actual: Any,
// mismatchFn: MismatchFactory[Mismatch]): List[Mismatch] = {
//    val value = matcherDef("max") match {
//      case i: Int => i
//      case j: Integer => j.toInt
//      case o => o.toString.toInt
//    }
//    logger.debug(s"comparing ${valueOf(actual)} with maximum $value at $path")
//    actual match {
//      case v: List[Any] =>
//        if (v.size > value) {
//          List(mismatchFn.create(expected, actual, s"Expected ${valueOf(actual)} to have maximum $value", path))
//        } else {
//          List()
//        }
//      case v: Elem =>
//        if (v.child.size > value) {
//          List(mismatchFn.create(expected, actual, s"Expected ${valueOf(actual)} to have minimum $value", path))
//        } else {
//          List()
//        }
//      case _ => TypeMatcher.domatch[Mismatch](matcherDef, path, expected, actual, mismatchFn)
//    }
//  }
//}

}
