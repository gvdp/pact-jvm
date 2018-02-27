package au.com.dius.pact.provider.broker

import au.com.dius.pact.pactbroker.InvalidHalResponse
import au.com.dius.pact.pactbroker.NotFoundHalResponse
import au.com.dius.pact.provider.broker.com.github.kittinunf.result.Result
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.ProtocolVersion
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.message.BasicHeader
import org.apache.http.message.BasicStatusLine
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import com.google.gson.JsonParser

import java.util.function.Consumer

@SuppressWarnings(['LineLength', 'UnnecessaryGetter', 'ClosureAsLastMethodParameter'])
class HalClientSpec extends Specification {

  private @Shared HalClient client
  private CloseableHttpClient mockClient

  def setup() {
    mockClient = Mock(CloseableHttpClient)
    client = GroovySpy(HalClient, global: true, constructorArgs: ['http://localhost:1234/'])
  }

  @SuppressWarnings(['LineLength', 'UnnecessaryBooleanExpression'])
  def 'can parse templated URLS correctly'() {
    expect:
    client.parseLinkUrl(url, options) == parsedUrl

    where:
    url                                                   | options              || parsedUrl
    ''                                                    | [:]                  || ''
    'http://localhost:8080/123456'                        | [:]                  || 'http://localhost:8080/123456'
    'http://docker:5000/pacts/provider/{provider}/latest' | [:]                  || 'http://docker:5000/pacts/provider/%7Bprovider%7D/latest'
    'http://docker:5000/pacts/provider/{provider}/latest' | [provider: 'test']   || 'http://docker:5000/pacts/provider/test/latest'
    'http://docker:5000/{b}/provider/{a}/latest'          | [a: 'a', b: 'b']     || 'http://docker:5000/b/provider/a/latest'
    '{a}://docker:5000/pacts/provider/{b}/latest'         | [a: 'test', b: 'b']  || 'test://docker:5000/pacts/provider/b/latest'
    'http://docker:5000/pacts/provider/{a}{b}'            | [a: 'test/', b: 'b'] || 'http://docker:5000/pacts/provider/test%2Fb'
  }

  @SuppressWarnings('UnnecessaryGetter')
  def 'matches authentication scheme case insensitive'() {
    given:
    client.options = [authentication: ['BASIC', '1', '2']]

    when:
    client.setupHttpClient()

    then:
    client.httpClient.credentialsProvider instanceof BasicCredentialsProvider
  }

  def 'throws an exception if the response is 404 Not Found'() {
    given:
    client.httpClient = mockClient
    def mockResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 404, 'Not Found')
    }

    when:
    client.navigate('pb:latest-provider-pacts')

    then:
    1 * mockClient.execute(_) >> mockResponse
    thrown(NotFoundHalResponse)
  }

  def 'throws an exception if the response is not JSON'() {
    given:
    client.httpClient = mockClient
    def contentType = new BasicHeader('Content-Type', 'text/plain')
    def mockBody = Mock(HttpEntity) {
      getContentType() >> contentType
    }
    def mockRootResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> mockBody
    }

    when:
    client.navigate('pb:latest-provider-pacts')

    then:
    1 * mockClient.execute({ it.getURI().path == '/' }) >> mockRootResponse
    thrown(InvalidHalResponse)
  }

  def 'throws an exception if the _links is not found'() {
    given:
    client.httpClient = mockClient
    def body = new StringEntity('{}', ContentType.APPLICATION_JSON)
    def mockRootResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> body
    }

    when:
    client.navigate('pb:latest-provider-pacts')

    then:
    1 * mockClient.execute({ it.getURI().path == '/' }) >> mockRootResponse
    thrown(InvalidHalResponse)
  }

  def 'throws an exception if the required link is not found'() {
    given:
    client.httpClient = mockClient
    def body = new StringEntity('{"_links":{}}', ContentType.APPLICATION_JSON)
    def mockRootResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> body
    }

    when:
    client.navigate('pb:latest-provider-pacts')

    then:
    1 * mockClient.execute({ it.getURI().path == '/' }) >> mockRootResponse
    thrown(InvalidHalResponse)
  }

  def 'Handles responses with charset attributes'() {
    given:
    client.httpClient = mockClient
    def contentType = new BasicHeader('Content-Type', 'application/hal+json;charset=UTF-8')
    def mockBody = Mock(HttpEntity) {
      getContentType() >> contentType
      getContent() >> new ByteArrayInputStream('{"_links": {"pb:latest-provider-pacts":{"href":"/link"}}}'.bytes)
    }
    def mockRootResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> mockBody
    }
    def mockResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> new StringEntity('{"_links":{}}', ContentType.create('application/hal+json'))
    }

    when:
    client.navigate('pb:latest-provider-pacts')

    then:
    1 * mockClient.execute({ it.getURI().path == '/' }) >> mockRootResponse
    1 * mockClient.execute({ it.getURI().path == '/link' }) >> mockResponse
    notThrown(InvalidHalResponse)
  }

  def 'does not throw an exception if the required link is empty'() {
    given:
    client.httpClient = mockClient
    def mockResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> new StringEntity('{"_links":{"pacts": []}}', ContentType.create('application/hal+json'))
    }

    when:
    def called = false
    client.pacts { called = true }

    then:
    1 * mockClient.execute({ it.getURI().path == '/' }) >> mockResponse
    !called
  }

  def 'uploading a JSON doc returns status line if successful'() {
    given:
    client.httpClient = mockClient
    def mockResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
    }

    when:
    def result = []
    def closure = { r, s -> result << r; result << s }
    client.uploadJson('/', '', closure)

    then:
    1 * mockClient.execute({ it.getURI().path == '/' }) >> mockResponse
    result == ['OK', 'http/1.1 200 Ok']
  }

  def 'uploading a JSON doc returns the error if unsuccessful'() {
    given:
    client.httpClient = mockClient
    def mockResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 400, 'Not OK')
      getEntity() >> new StringEntity('{"errors":["1","2","3"]}', ContentType.create('application/json'))
    }

    when:
    def result = []
    def closure = { r, s -> result << r; result << s }
    client.uploadJson('/', '', closure)

    then:
    result == ['FAILED', '400 Not OK - 1, 2, 3']
    1 * mockClient.execute({ it.getURI().path == '/' }) >> mockResponse
  }

  def 'uploading a JSON doc returns the error if unsuccessful due to 409'() {
    given:
    client.httpClient = mockClient
    def mockResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 409, 'Not OK')
      getEntity() >> new StringEntity('error line')
    }

    when:
    def result = []
    def closure = { r, s -> result << r; result << s }
    client.uploadJson('/', '', closure)

    then:
    1 * mockClient.execute({ it.getURI().path == '/' }) >> mockResponse
    result == ['FAILED', '409 Not OK - error line']
  }

  @Unroll
  def 'failure handling - #description'() {
    given:
    client.httpClient = mockClient
    def statusLine = new BasicStatusLine(new ProtocolVersion('HTTP', 1, 1), 400, 'Not OK')
    def resp = [
      getStatusLine: { statusLine },
      getEntity: { [getContentType: { new BasicHeader('Content-Type', 'application/json') } ] as HttpEntity }
    ] as HttpResponse

    expect:
    client.handleFailure(resp, body) { arg1, arg2 -> [arg1, arg2] } == [firstArg, secondArg]

    where:

    description                                | body                               | firstArg | secondArg
    'body is null'                             | null                               | 'FAILED' | '400 Not OK - Unknown error'
    'body is a parsed json doc with no errors' | '{}'                               | 'FAILED' | '400 Not OK - Unknown error'
    'body is a parsed json doc with errors'    | '{"errors":["one","two","three"]}' | 'FAILED' | '400 Not OK - one, two, three'

  }

  @Unroll
  @SuppressWarnings('UnnecessaryGetter')
  def 'post URL returns #success if the response is #status'() {
    given:
    def mockClient = Mock(CloseableHttpClient)
    client.httpClient = mockClient
    def mockResponse = Mock(CloseableHttpResponse)
    1 * mockClient.execute(_) >> mockResponse
    1 * mockResponse.getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), status, 'OK')

    expect:
    client.postJson('path', 'body') == expectedResult

    where:

    success   | status | expectedResult
    'success' | 200    | new Result.Success(true)
    'failure' | 400    | new Result.Success(false)
  }

  def 'post URL returns a failure result if an exception is thrown'() {
    given:
    def mockClient = Mock(CloseableHttpClient)
    client.httpClient = mockClient

    when:
    def result = client.postJson('path', 'body')

    then:
    1 * mockClient.execute(_) >> { throw new IOException('Boom!') }
    result instanceof Result.Failure
  }

  @SuppressWarnings('UnnecessaryGetter')
  def 'post URL delegates to a handler if one is supplied'() {
    given:
    def mockClient = Mock(CloseableHttpClient)
    client.httpClient = mockClient
    def mockResponse = Mock(CloseableHttpResponse)
    1 * mockClient.execute(_) >> mockResponse
    1 * mockResponse.getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'OK')

    when:
    def result = client.postJson('path', 'body') { status, resp -> false }

    then:
    result == new Result.Success(false)
  }

  def 'forAll does nothing if there is no matching link'() {
    given:
    client.httpClient = mockClient
    def mockResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> new StringEntity('{"_links":{}}', ContentType.create('application/hal+json'))
    }
    def closure = Mock(Consumer)

    when:
    client.forAll('missingLink', closure)

    then:
    1 * mockClient.execute({ it.getURI().path == '/' }) >> mockResponse
    0 * closure.accept(_)
  }

  def 'forAll calls the closure with the link data'() {
    given:
    client.httpClient = mockClient
    def mockResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> new StringEntity('{"_links":{"simpleLink": {"link": "linkData"}}}',
        ContentType.create('application/hal+json'))
    }
    def closure = Mock(Consumer)

    when:
    client.forAll('simpleLink', closure)

    then:
    1 * mockClient.execute({ it.getURI().path == '/' }) >> mockResponse
    1 * closure.accept([link: 'linkData'])
  }

  def 'forAll calls the closure with each link data when the link is a collection'() {
    given:
    client.httpClient = mockClient
    def mockResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> new StringEntity('{"_links":{"multipleLink": [{"href":"one"}, {"href":"two"}, {"href":"three"}]}}',
        ContentType.create('application/hal+json'))
    }
    def closure = Mock(Consumer)

    when:
    client.forAll('multipleLink', closure)

    then:
    1 * mockClient.execute({ it.getURI().path == '/' }) >> mockResponse
    1 * closure.accept([href: 'one'])
    1 * closure.accept([href: 'two'])
    1 * closure.accept([href: 'three'])
  }

  def 'supports templated URLs with slashes in the expanded values'() {
    given:
    def providerName = 'test/provider name-1'
    def tag = 'test/tag name-1'
    client.httpClient = mockClient
    def body = new StringEntity('{"_links":{"pb:latest-provider-pacts-with-tag": ' +
      '{"href": "http://localhost/{provider}/tag/{tag}", "templated": true}}}', ContentType.APPLICATION_JSON)
    def mockRootResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> body
    }
    def mockResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> new StringEntity('{"_links":{"linkA": "ValueA"}}', ContentType.create('application/hal+json'))
    }
    def notFoundResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 404, 'Not Found')
    }

    when:
    client.navigate('pb:latest-provider-pacts-with-tag', provider: providerName, tag: tag)

    then:
    1 * mockClient.execute({ it.URI.path == '/' }) >> mockRootResponse
    1 * mockClient.execute({ it.URI.rawPath == '/test%2Fprovider%20name-1/tag/test%2Ftag%20name-1' }) >> mockResponse
    _ * mockClient.execute(_) >> notFoundResponse
    client.pathInfo['_links']['linkA'].toString() == '"ValueA"'
  }

  def 'handles invalid URL characters when fetching documents from the broker'() {
    given:
    client.httpClient = mockClient
    def mockResponse = Mock(CloseableHttpResponse) {
      getStatusLine() >> new BasicStatusLine(new ProtocolVersion('http', 1, 1), 200, 'Ok')
      getEntity() >> new StringEntity('{"_links":{"multipleLink": ["one", "two", "three"]}}',
        ContentType.create('application/hal+json'))
    }

    when:
    def result = client.fetch('https://test.pact.dius.com.au/pacts/provider/Activity Service/consumer/Foo Web Client 2/version/1.0.2')

    then:
    1 * mockClient.execute({ it.URI.toString() == 'https://test.pact.dius.com.au/pacts/provider/Activity%20Service/consumer/Foo%20Web%20Client%202/version/1.0.2' }) >> mockResponse
    result['_links']['multipleLink']*.toString() == ['"one"', '"two"', '"three"']
  }

  @Unroll
  def 'link url test'() {
    given:
    client.pathInfo = new JsonParser().parse(json)

    expect:
    client.linkUrl(name) == url

    where:

    json                                        | name   | url
    '{}'                                        | 'test' | null
    '{"_links": null}'                          | 'test' | null
    '{"_links": "null"}'                        | 'test' | null
    '{"_links": {}}'                            | 'test' | null
    '{"_links": { "test": null }}'              | 'test' | null
    '{"_links": { "test": "null" }}'            | 'test' | null
    '{"_links": { "test": {} }}'                | 'test' | null
    '{"_links": { "test": { "blah": "123" } }}' | 'test' | null
    '{"_links": { "test": { "href": "123" } }}' | 'test' | '123'
    '{"_links": { "test": { "href": 123 } }}'   | 'test' | '123'
  }

  @Unroll
  def 'from JSON test'() {
    expect:
    HalClient.fromJson(new JsonParser().parse(json)) == value

    where:

    json                                                    | value
    'null'                                                  | null
    '100'                                                   | 100
    '100.3'                                                 | 100.3
    'true'                                                  | true
    '"a string value"'                                      | 'a string value'
    '[]'                                                    | []
    '["a string value"]'                                    | ['a string value']
    '["a string value", 2]'                                 | ['a string value', 2]
    '{}'                                                    | [:]
    '{"a": "A", "b": 1, "c": [100], "d": {"href": "blah"}}' | [a: 'A', b: 1, c: [100], d: [href: 'blah']]
  }

}
