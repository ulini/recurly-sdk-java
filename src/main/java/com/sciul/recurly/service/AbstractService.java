package com.sciul.recurly.service;

import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXBContext;

import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.util.URIUtil;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.xml.sax.InputSource;

import com.sciul.recurly.config.RecurlyConfiguration;
import com.sciul.recurly.exception.RecurlyException;
import com.sciul.recurly.helper.BillingConstants;
import com.sciul.recurly.model.RecurlyError;
import com.sciul.recurly.model.RecurlyErrors;
import com.sciul.sdk.helper.SNSNotification;
import com.sciul.sdk.model.Payload;

/**
 * The Class AbstractService.
 * 
 * @author GauravChawla
 */
public abstract class AbstractService {

  /**
   * The logger.
   * 
   * @return the logger
   */
  public abstract Logger getLogger();

  /** The recurly. */
  @Autowired
  private RecurlyConfiguration recurly;

  /** The sns client. */
  @Autowired
  private SNSNotification snsClient;

  /** The rest template. */
  private static RestTemplate restTemplate = new RestTemplate();

  /**
   * Call recurly API.
   * 
   * @param <T>
   *          the generic type
   * @param <E>
   *          the element type
   * @param path
   *          the path
   * @param payload
   *          the payload
   * @param responseClass
   *          the response class
   * @param method
   *          the method
   * @return the t
   * @throws RecurlyException
   *           the recurly exception
   */
  protected <T, E> T call(String path, E payload, Class<T> responseClass, HttpMethod method) throws RecurlyException {

    URI uri = null;
    ResponseEntity<T> responseEntity = null;
    T reponse = null;
    try {
      HttpEntity<?> entity = new HttpEntity<>(payload, recurly.getRecurlyHeaders());
      uri = new URI(URIUtil.encodeQuery(recurly.getRecurlyServerURL() + path, "UTF-8"));
      getLogger().debug("Calling Recurly URL {}, method: {}", uri.toString(), method.toString());
      responseEntity = restTemplate.exchange(uri, method, entity, responseClass);
      if (responseEntity != null)
        reponse = responseEntity.getBody();

    } catch (URIException | UnsupportedEncodingException | URISyntaxException e) {
      throw new RecurlyException("Not able to reach recurly. Please check the URL.", e);
    } catch (RestClientException e) {
      String err = ((HttpStatusCodeException) e).getResponseBodyAsString();
      int code = ((HttpStatusCodeException) e).getStatusCode().value();
      publishError(uri, err, code);
      RecurlyException ex = handleError(err, code, e);
      throw ex;
    }
    return reponse;
  }

  /**
   * Handle recurly error.
   * 
   * @param err
   *          the error message
   * @param code
   *          the error code
   * @param ex
   *          the exception
   * @return the recurly exception
   */
  private RecurlyException handleError(String err, int code, Exception ex) {
    RecurlyErrors errors = null;
    try {
      if (err.indexOf(BillingConstants.RECURLY_ERRORS) != -1) {
        errors =
              (RecurlyErrors) JAXBContext.newInstance(RecurlyErrors.class).createUnmarshaller()
                    .unmarshal(new InputSource(new StringReader(err)));
      } else if (err.indexOf(BillingConstants.RECURLY_ERROR) != -1) {
        RecurlyError error =
              (RecurlyError) JAXBContext.newInstance(RecurlyError.class).createUnmarshaller()
                    .unmarshal(new InputSource(new StringReader(err)));
        errors = new RecurlyErrors();
        List<RecurlyError> errorList = new ArrayList<RecurlyError>();
        errorList.add(error);
        errors.setError(errorList);
      }
    } catch (Exception e) {
      getLogger().error("JAXBContext Failed {} ", e);
      return new RecurlyException(err, code, e);
    }
    String error = null;
    for (RecurlyError e : errors.getError()) {
      if (error == null)
        error = e.getSymbol() + " : " + e.getDescription();
      else
        error = error + " | " + e.getSymbol() + " : " + e.getDescription();
    }
    return new RecurlyException(error, code, ex);
  }

  /**
   * Publish error via AWS SNS.
   * 
   * @param uri
   *          the uri
   * @param err
   *          the error
   * @param code
   *          the error http code
   */
  private void publishError(URI uri, String error, Integer code) {
    getLogger().error("Recurly API: {} - Error:  {}", uri.toString(), error);
    if (recurly.getSnsTopic() != null && !recurly.getSnsTopic().isEmpty() && recurly.getAwsTopicRegion() != null
          && !recurly.getAwsTopicRegion().isEmpty()) {
      String msgId =
            snsClient.publish("URL:: " + uri.toString() + " " + error, recurly.getSnsTopic(),
                  recurly.getAwsTopicRegion(), "Recurly Error: " + code);
      getLogger().info("Sending Recurly Error via SNS - MessageId: {}", msgId);
    }
    if (recurly.getChannel() != null && !recurly.getChannel().equalsIgnoreCase("")) {
      getLogger().info("Sending Recurly Error via Slack to channel: {}", recurly.getChannel());
      publishToSlack(error);
    } else {
      getLogger().info("Sending Recurly Error via failed for Slack");
    }
  }

  /**
   * Publish to slack.
   * 
   * @param message
   *          the error message
   * @throws URIException
   */
  private void publishToSlack(String error) {
    URI uri = null;
    HttpMethod method = HttpMethod.POST;
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.set("Content-Type", "application/json; charset=utf-8");
      Payload payload = new Payload(error);
      HttpEntity<?> entity = new HttpEntity<>(payload, headers);
      uri = new URI(URIUtil.encodeQuery(recurly.getChannel(), "UTF-8"));
      getLogger().debug("Calling Slack URL {}, method: {}", uri.toString(), method.toString());
      restTemplate.exchange(uri, method, entity, void.class);
    } catch (URIException | URISyntaxException | RestClientException e) {
      getLogger().error("Calling Slack URL {}, error: {}", uri.toString(), e);
    }
  }
}