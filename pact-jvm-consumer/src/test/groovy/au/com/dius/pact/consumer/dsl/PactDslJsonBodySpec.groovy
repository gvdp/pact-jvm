package au.com.dius.pact.consumer.dsl

import au.com.dius.pact.model.Feature
import au.com.dius.pact.model.FeatureToggles
import au.com.dius.pact.model.PactSpecVersion
import au.com.dius.pact.model.matchingrules.MatchingRuleGroup
import au.com.dius.pact.model.matchingrules.MinTypeMatcher
import au.com.dius.pact.model.matchingrules.RegexMatcher
import au.com.dius.pact.model.matchingrules.RuleLogic
import au.com.dius.pact.model.matchingrules.TypeMatcher
import au.com.dius.pact.model.matchingrules.ValuesMatcher
import spock.lang.Specification
import spock.lang.Unroll

class PactDslJsonBodySpec extends Specification {

  def 'close must close off all parents and return the root'() {
    given:
      def root = new PactDslJsonBody()
      def array = new PactDslJsonArray('b', '', root)
      def obj = new PactDslJsonBody('c', '', array)

    when:
      def result = obj.close()

    then:
      root.closed
      obj.closed
      array.closed
      result.is root
  }

  @Unroll
  def 'min array like function should set the example size to the min size'() {
    expect:
    obj.close().body.getJSONArray('test').length() == 2

    where:
    obj << [
      new PactDslJsonBody().minArrayLike('test', 2).id(),
      new PactDslJsonBody().minArrayLike('test', 2, PactDslJsonRootValue.id())
    ]
  }

  def 'min array like function should validate the number of examples match the min size'() {
    when:
    new PactDslJsonBody().minArrayLike('test', 3, 2)

    then:
    thrown(IllegalArgumentException)
  }

  def 'min array like function with root value should validate the number of examples match the min size'() {
    when:
    new PactDslJsonBody().minArrayLike('test', 3, PactDslJsonRootValue.id(), 2)

    then:
    thrown(IllegalArgumentException)
  }

  def 'max array like function should validate the number of examples match the max size'() {
    when:
    new PactDslJsonBody().maxArrayLike('test', 3, 4)

    then:
    thrown(IllegalArgumentException)
  }

  def 'max array like function with root value should validate the number of examples match the max size'() {
    when:
    new PactDslJsonBody().minArrayLike('test', 4, PactDslJsonRootValue.id(), 3)

    then:
    thrown(IllegalArgumentException)
  }

  def 'each array with max like function should validate the number of examples match the max size'() {
    when:
    new PactDslJsonBody().eachArrayWithMaxLike('test', 4, 3)

    then:
    thrown(IllegalArgumentException)
  }

  def 'each array with min function should validate the number of examples match the min size'() {
    when:
    new PactDslJsonBody().eachArrayWithMinLike('test', 2, 3)

    then:
    thrown(IllegalArgumentException)
  }

  def 'with nested objects, the rule logic value should be copied'() {
    expect:
    body.matchers.matchingRules['.foo.bar'].ruleLogic == RuleLogic.OR

    where:
    body = new PactDslJsonBody().object('foo')
      .or('bar', 42, PM.numberType(), PM.nullValue())
      .closeObject()
  }

  def 'generate the correct JSON when the attribute name is a number'() {
    expect:
    new PactDslJsonBody()
      .stringType('asdf')
      .array('0').closeArray()
      .eachArrayLike('1').closeArray().closeArray()
      .eachArrayWithMaxLike('2', 10).closeArray().closeArray()
      .eachArrayWithMinLike('3', 10).closeArray().closeArray()
            .close().toString() == '{"0":[],"1":[[]],"2":[[]],"3":[[],[],[],[],[],[],[],[],[],[]],"asdf":"string"}'
  }

  def 'generate the correct JSON when the attribute name has a space'() {
    expect:
    new PactDslJsonBody()
      .array('available Options')
        .object()
        .stringType('Material', 'Gold')
      . closeObject()
      .closeArray().toString() == '{"available Options":[{"Material":"Gold"}]}'
  }

  def 'test for behaviour of close for issue #619'() {
    given:
    PactDslJsonBody pactDslJsonBody = new PactDslJsonBody()
    PactDslJsonBody contactDetailsPactDslJsonBody = pactDslJsonBody.object('contactDetails')
    contactDetailsPactDslJsonBody.object('mobile')
      .stringType('countryCode', '64')
      .stringType('prefix', '21')
      .stringType('subscriberNumber', '123456')
      .closeObject()
    pactDslJsonBody = contactDetailsPactDslJsonBody.closeObject().close()

    expect:
    pactDslJsonBody.close().matchers.toMap(PactSpecVersion.V2) == [
      '$.body.contactDetails.mobile.countryCode': [match: 'type'],
      '$.body.contactDetails.mobile.prefix': [match: 'type'],
      '$.body.contactDetails.mobile.subscriberNumber': [match: 'type']
    ]
  }

  def 'eachKey - generate a wildcard matcher pattern if useMatchValuesMatcher is not set'() {
    given:
    FeatureToggles.toggleFeature(Feature.UseMatchValuesMatcher, false)

    def pactDslJsonBody = new PactDslJsonBody()
      .object('one')
        .eachKeyLike('key1')
          .id()
          .closeObject()
      .closeObject()
      .object('two')
        .eachKeyLike('key2', PactDslJsonRootValue.stringMatcher('\\w+', 'test'))
      .closeObject()
      .object('three')
        .eachKeyMappedToAnArrayLike('key3')
          .id('key3-id')
          .closeObject()
        .closeArray()
      .closeObject()

    when:
    pactDslJsonBody.close()

    then:
    pactDslJsonBody.matchers.matchingRules == [
      '$.one.*': new MatchingRuleGroup([TypeMatcher.INSTANCE]),
      '$.one.*.id': new MatchingRuleGroup([TypeMatcher.INSTANCE]),
      '$.two.*': new MatchingRuleGroup([new RegexMatcher('\\w+')]),
      '$.three.*': new MatchingRuleGroup([new MinTypeMatcher(0)]),
      '$.three.*[*].key3-id': new MatchingRuleGroup([TypeMatcher.INSTANCE])
    ]

    cleanup:
    FeatureToggles.reset()
  }

  def 'eachKey - generate a match values matcher if useMatchValuesMatcher is set'() {
    given:
    FeatureToggles.toggleFeature(Feature.UseMatchValuesMatcher, true)

    def pactDslJsonBody = new PactDslJsonBody()
      .object('one')
      .eachKeyLike('key1')
      .id()
      .closeObject()
      .closeObject()
      .object('two')
      .eachKeyLike('key2', PactDslJsonRootValue.stringMatcher('\\w+', 'test'))
      .closeObject()
      .object('three')
      .eachKeyMappedToAnArrayLike('key3')
      .id('key3-id')
      .closeObject()
      .closeArray()
      .closeObject()

    when:
    pactDslJsonBody.close()

    then:
    pactDslJsonBody.matchers.matchingRules == [
      '$.one': new MatchingRuleGroup([ValuesMatcher.INSTANCE]),
      '$.one.*.id': new MatchingRuleGroup([TypeMatcher.INSTANCE]),
      '$.two': new MatchingRuleGroup([ValuesMatcher.INSTANCE]),
      '$.two.*': new MatchingRuleGroup([new RegexMatcher('\\w+')]),
      '$.three': new MatchingRuleGroup([ValuesMatcher.INSTANCE]),
      '$.three.*[*].key3-id': new MatchingRuleGroup([TypeMatcher.INSTANCE])
    ]

    cleanup:
    FeatureToggles.reset()
  }

}
