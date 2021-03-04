package com.fchen_group.TPDSInScf.Run;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fchen_group.TPDSInScf.Core.ChallengeData;
import com.fchen_group.TPDSInScf.Core.IntegrityAuditing;
import com.fchen_group.TPDSInScf.Core.ProofData;
import com.fchen_group.TPDSInScf.Utils.CloudAPI;
import sun.net.www.protocol.https.Handler;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class Client {
    public static void main(String args[]) throws IOException {

        String filePath = "E:\\Gitfolder\\tpds\\tpdsTestData\\cache.rar";
        String cosConfigFilePath = "E:\\infosecProject\\myCloudProperties\\Properties.properties";

        Scanner scan = new Scanner(System.in);
        System.out.println("Please input the BLOCK_SHARDS：");
        int BLOCK_SHARDS = scan.nextInt();
        System.out.println("Please input the DATA_SHARDS：");
        int DATA_SHARDS = scan.nextInt();
        System.out.println("The ReedSolomon parameters: (BLOCK_SHARDS, DATA_SHARDS)=(" + BLOCK_SHARDS + "," + DATA_SHARDS + ")");

        IntegrityAuditing integrityAuditing = new IntegrityAuditing(filePath, BLOCK_SHARDS, DATA_SHARDS);
        //start auditing
        integrityAuditing.genKey();
        System.out.println("keyGen phase finished");

        //cal tags , divide source file by block,and then upload tags and file block
        integrityAuditing.outSource();// tags ,source file were ready
        String uploadSourceFilePath = "E:\\Gitfolder\\tpds\\tpdsUploadFile\\sourceFile.txt";
        String uploadParitiesPath = "E:\\Gitfolder\\tpds\\tpdsUploadFile\\parities.txt";

        //firstly store file in local
        File uploadSourceFile = new File(uploadSourceFilePath);
        uploadSourceFile.createNewFile();
        OutputStream osFile = new FileOutputStream(uploadSourceFile, false);
        for (int i = 0; i < integrityAuditing.originalData.length; i++) {
            osFile.write(integrityAuditing.originalData[i]);
        }
        osFile.close();
        System.out.println("store file in local");
        //test
        System.out.println("print JSON STR of original");
        System.out.println(JSON.toJSONString(integrityAuditing.originalData));

        //firstly store tags in local
        File uploadParities = new File(uploadParitiesPath);
        uploadParities.createNewFile();
        OutputStream osParities = new FileOutputStream(uploadParities, false);
        for (int i = 0; i < integrityAuditing.parity.length; i++) {
            osParities.write(integrityAuditing.parity[i]);
        }
        osParities.close();
        System.out.println("store file and tags in local");

//TEST
        System.out.println("Original data");
       // System.out.println(JSON.toJSONString(integrityAuditing.originalData));

        //upload File and tags to COS
        CloudAPI cloudAPI = new CloudAPI("E:\\infosecProject\\myCloudProperties\\Properties.properties");
        cloudAPI.uploadFile(uploadSourceFilePath, "sourceFile.txt");
        cloudAPI.uploadFile(uploadParitiesPath, "parities.txt");
        System.out.println("upload File and tags to COS");

        //trigger ScfHandle
        //get connection
        //String reqPath = "https://service-93qcoxs2-1302225808.gz.apigw.tencentcs.com/release/TPDSInScf-1614677931";
        String reqPath = "https://service-7hhv3bd0-1302225808.gz.apigw.tencentcs.com/release/SCFTest";
        URL url = new URL(null, reqPath, new Handler());
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);

        //write header
        connection.setRequestProperty("Content-Type", "application/json");

        //prepare challengeData
        ChallengeData challengeData = integrityAuditing.audit(460);

        //write body , and send the challenge data to SCF and trigger SCF to start audit
        try (PrintWriter writer = new PrintWriter(connection.getOutputStream())) {
            Map<String, String> foo = new HashMap<>();
            foo.put("bucketName", "tpds-in-scf-bucket-1302225808");
            foo.put("regionName", "ap-guangzhou");
            foo.put("DATA_SHARDS", Integer.toString(DATA_SHARDS));
            foo.put("PARITY_SHARDS", Integer.toString((BLOCK_SHARDS-DATA_SHARDS)));
            writer.write(JSON.toJSONString(foo));
            writer.write(JSON.toJSONString(challengeData));
            System.out.println("challengeData str:"+JSON.toJSONString(challengeData));
            writer.flush();
        }
        System.out.println("Waiting SCF return Proof for verifying");

        //read response
        String responseDataStr = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("read from response:" + line);
                if (line.indexOf("body") != -1) { //只需要body的内容
                    responseDataStr = line;
                }

            }
        } finally {
            connection.disconnect();
        }
        //提取body 中序列化的proof data
        System.out.println("receive body str");
        System.out.println(responseDataStr);
        int indexOfDataProof = responseDataStr.indexOf("dataProof");
        String targetStr = responseDataStr.substring(indexOfDataProof-2,responseDataStr.length()-1);

        ProofData proofData = JSON.parseObject(targetStr,ProofData.class);
        System.out.println("Get proofData content:"+proofData.dataProof.toString()+"\t and"+proofData.parityProof.toString());

        //execute verify parse
        if(integrityAuditing.verify(challengeData,proofData)){
            System.out.println("The data is intact in the cloud.The auditing process is success!");
        }
    }
}
