package com.fchen_group.TPDSInScf.Run;

import com.alibaba.fastjson.JSON;
import com.fchen_group.TPDSInScf.Core.ChallengeData;
import com.fchen_group.TPDSInScf.Core.IntegrityAuditing;
import com.fchen_group.TPDSInScf.Core.ProofData;
import com.fchen_group.TPDSInScf.Utils.CloudAPI;
import com.qcloud.services.scf.runtime.events.APIGatewayProxyRequestEvent;
import com.qcloud.services.scf.runtime.events.APIGatewayProxyResponseEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class define the action of the serverless cloud function in the audit process
 *
 * @author jquan, fchen-group of SZU
 * @Version 2.0 2021.12.02
 * @Time 2021.3 - 2021.12
 */
public class ScfHandle {
    /**
     * the method triggered by APIGateway event
     */
    public APIGatewayProxyResponseEvent main(APIGatewayProxyRequestEvent event) {
        Logger logger = Logger.getLogger("AnyLoggerName");
        logger.setLevel(Level.INFO);

        APIGatewayProxyRequestEvent req = event;
        //System.out.println("request's body content is=" + req.getBody());

        //get challengeData from the body
        ChallengeData challengeData = null;
        String receiveBody = req.getBody();
        String reGetDataStr;
        if (receiveBody.contains("coefficients")) {
            int indexOfCoe = receiveBody.indexOf("coefficients");
            reGetDataStr = receiveBody.substring(indexOfCoe - 2);
            // System.out.println("reGetDataStr of challengeData:" + reGetDataStr);
            challengeData = JSON.parseObject(reGetDataStr, ChallengeData.class);
        } else {
            System.out.println("SCF receive challenge data unsuccessfully");
        }

        //get bucketName and regionName from body
        String bucketName = "";
        String regionName = "";
        if (receiveBody.contains("bucketName")) {
            int indexOfBucketName = receiveBody.indexOf("bucketName");
            int indexOfRegionName = receiveBody.indexOf("regionName");
            char[] bodyArray = receiveBody.toCharArray();
            StringBuilder bufferBucket = new StringBuilder();
            //Body Format:{}{"bucketName":"tpds-in-scf-bucket-1302225808"}{"regionName":"ap-guangzhou"}
            //get bucketName
            for (int i = (indexOfBucketName + 13); i < bodyArray.length; i++) {
                if (bodyArray[i] == '"') break;

                bufferBucket.append(bodyArray[i]);
            }
            bucketName = bufferBucket.toString();
            System.out.println("received bucketName : " + bucketName);

            //get regionName
            StringBuilder bufferRegion = new StringBuilder();
            for (int i = (indexOfRegionName + 13); i < bodyArray.length; i++) {
                if (bodyArray[i] == '"') break;

                bufferRegion.append(bodyArray[i]);
            }
            regionName = bufferRegion.toString();
            System.out.println("received regionName : " + regionName);
        } else {
            System.out.println("bucketName and regionName received unsuccessfully");
        }

        //get DATA_SHARDS,PARITY_SHARDS
        int DATA_SHARDS = 0;
        int PARITY_SHARDS = 0;
        String DATA_SHARDS_Str = "";
        String PARITY_SHARDS_Str = "";

        if (receiveBody.contains("DATA_SHARDS") && receiveBody.contains("PARITY_SHARDS")) {
            int indexOfDATA_SHARDS = receiveBody.indexOf("DATA_SHARDS");
            int indexOfPARITY_SHARDS = receiveBody.indexOf("PARITY_SHARDS");
            char[] bodyArray = receiveBody.toCharArray();
            StringBuilder bufferDATA_SHARDS = new StringBuilder();
            //Body Format:{}{"bucketName":"tpds-in-scf-bucket-1302225808"}{"regionName":"ap-guangzhou"}{"DATA_SHARDS":"223"}{"PARITY_SHARDS":"32"}
            //get DATA_SHARDS
            for (int i = (indexOfDATA_SHARDS + 14); i < bodyArray.length; i++) {
                if (bodyArray[i] == '"') break;

                bufferDATA_SHARDS.append(bodyArray[i]);
            }
            DATA_SHARDS_Str = bufferDATA_SHARDS.toString();

            //get PARITY_SHARDS
            StringBuilder bufferPARITY_SHARDS = new StringBuilder();
            for (int i = (indexOfPARITY_SHARDS + 16); i < bodyArray.length; i++) {
                if (bodyArray[i] == '"') break;

                bufferPARITY_SHARDS.append(bodyArray[i]);
            }
            PARITY_SHARDS_Str = bufferPARITY_SHARDS.toString();
            DATA_SHARDS = Integer.parseInt(DATA_SHARDS_Str);
            PARITY_SHARDS = Integer.parseInt(PARITY_SHARDS_Str);

            System.out.println("DATA_SHARDS+PARITY_SHARDS= " + (DATA_SHARDS + PARITY_SHARDS));
        } else {
            System.out.println("  DATA_SHARDS,PARITY_SHARDS  received unsuccessfully");
        }

        //initial cosClient

        /*
        * Most secure way is to read from environment variable,
        * however there is a bug with the corresponding API,
        * we have confirmed this bug with the Tencent Cloud and gave feedback to them
        *
        * */

        //Most secure way: Initialize cosclient with temporary key
        /*String secretId = System.getProperty("TENCENTCLOUD_SECRETID");
        String secretKey = System.getProperty("TENCENTCLOUD_SECRETKEY");
        String sessionToken = System.getProperty("TENCENTCLOUD_SESSIONTOKEN");
        System.out.println("secretId: "+secretId);
        System.out.println("secretKey: "+secretKey);
        System.out.println("sessionToken: "+sessionToken);*/

        String secretId = ""; //Using your cloud Id and Key
        String secretKey = "";//
        CloudAPI cloudAPI = new CloudAPI(secretId, secretKey, regionName, bucketName);

        //get ProofData from cloud by using challengeData from cloud
        IntegrityAuditing integrityAuditing = new IntegrityAuditing(DATA_SHARDS, PARITY_SHARDS);
        byte[][] downloadData = new byte[challengeData.index.length][DATA_SHARDS];
        byte[][] downloadParity = new byte[challengeData.index.length][PARITY_SHARDS];
        for (int i = 0; i < challengeData.index.length; i++) {
            downloadData[i] = cloudAPI.downloadPartFile("sourceFile.txt", challengeData.index[i] * DATA_SHARDS, DATA_SHARDS);
            downloadParity[i] = cloudAPI.downloadPartFile("parities.txt", challengeData.index[i] * PARITY_SHARDS, PARITY_SHARDS);
        }
        System.out.println("down load from COS successfully");

        ProofData proofData = integrityAuditing.prove(challengeData, downloadData, downloadParity);

        APIGatewayProxyResponseEvent rep = new APIGatewayProxyResponseEvent();
        rep.setBody(JSON.toJSONString(proofData));
        rep.setIsBase64Encoded(false);
        rep.setStatusCode(200);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/html");
        rep.setHeaders(headers);

        return rep;


    }
}
