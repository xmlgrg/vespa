/*
 * Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
 */

package ai.vespa.metricsproxy.client.cloudwatch;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;
import com.amazonaws.services.securitytoken.model.GetSessionTokenRequest;
import com.amazonaws.services.securitytoken.model.GetSessionTokenResult;
import com.yahoo.component.AbstractComponent;

import java.time.Instant;

/**
 * Publishes custom metrics to CloudWatch.
 *
 * @author gjoranv
 */
public class CloudWatchPublisher extends AbstractComponent {

    // TODO: Does this class need the AWS region? It is not available from the config-model (COLO in Zones)

    // TODO: Need tenant.app.instance! Model gets it from deployState.getProperties().applicationId(). Propagate in config.

    private final CloudwatchClientConfig config;
    private final AmazonCloudWatch cloudWatchClient;

    public CloudWatchPublisher(CloudwatchClientConfig config) {
        this.config = config;
        cloudWatchClient = getClient("foo", "bar");
    }

    /**
     * Retrieve
     * @return
     */
    private Credentials getVespaCredentials() {

    }

    private Credentials getApplicationCredentials() {
        AWSSecurityTokenService stsClient = AWSSecurityTokenServiceClientBuilder.standard().build();
        AssumeRoleRequest assumeRoleRequest = new AssumeRoleRequest()
                .withRoleArn(config.application().iamRole())
                .withExternalId(config.application().externalId())
                .withRoleSessionName(config.sessionName());

        AssumeRoleResult result = stsClient.assumeRole(assumeRoleRequest);
        Credentials resultCredentials = result.getCredentials();
        BasicSessionCredentials credentials = new BasicSessionCredentials(resultCredentials.getAccessKeyId(),
                                                                          resultCredentials.getSecretAccessKey(),
                                                                          resultCredentials.getSessionToken());
        Instant sessionExpiry = resultCredentials.getExpiration().toInstant();
        GetSessionTokenResult sessionToken = stsClient.getSessionToken(new GetSessionTokenRequest());
        return sessionToken.getCredentials();
    }

    private AmazonCloudWatch getClient(String accessKey, String secretKey) {
        Credentials credentials = getApplicationCredentials(); // TODO: cache?
        return AmazonCloudWatchClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
                .withClientConfiguration(new ClientConfiguration())
                //.withRegion(region) TODO: check if this is necessary
                .build();
    }

    @Override
    public void deconstruct() {
        cloudWatchClient.shutdown();
        super.deconstruct();
    }

}
