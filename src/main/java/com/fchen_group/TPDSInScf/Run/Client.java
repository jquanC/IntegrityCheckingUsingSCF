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

        String filePath = "E:\\Gitfolder\\tpds\\tpdsTestData\\randomFile10M.rar";

        Scanner scan = new Scanner(System.in);
        System.out.println("Please input the BLOCK_SHARDS：");
        int BLOCK_SHARDS = scan.nextInt();
        System.out.println("Please input the DATA_SHARDS：");
        int DATA_SHARDS = scan.nextInt();

        System.out.println("The ReedSolomon parameters: (BLOCK_SHARDS, DATA_SHARDS)=(" + BLOCK_SHARDS + "," + DATA_SHARDS + ")");
        auditTask(filePath, BLOCK_SHARDS, DATA_SHARDS , 1);
    }
    public static void auditTask(String filePath, int BLOCK_SHARDS, int DATA_SHARDS ,int taskCount) throws IOException {
        IntegrityAuditing integrityAuditing = new IntegrityAuditing(filePath, BLOCK_SHARDS, DATA_SHARDS);
        String cosConfigFilePath = "E:\\infosecProject\\myCloudProperties\\Properties.properties";
        long time[] = new long[5];//0-KeyGen , 1-DataProcess , 2-OutSource , 3-Audit , 4-Verify ,x-Prove(从腾讯云控制台读取)

        //start auditing
        System.out.println("---KeyGen phase start---");
        long start_time_genKey = System.nanoTime();
        integrityAuditing.genKey();
        long end_time_genKey = System.nanoTime();
        time[0] = end_time_genKey-start_time_genKey;
        System.out.println("---KeyGen phase finished---");

        //cal tags , divide source file by block,and then upload tags and file block
        System.out.println("---OutSource phase start---");
        time[1] = integrityAuditing.outSource();// tags ,source file were ready ; return data process time
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
        //cal source file size
        long sourceFileSize = uploadSourceFile.length();
        //test
       // System.out.println("print JSON STR of original");
        //System.out.println(JSON.toJSONString(integrityAuditing.originalData));

        //then store tags in local
        File uploadParities = new File(uploadParitiesPath);
        uploadParities.createNewFile();
        OutputStream osParities = new FileOutputStream(uploadParities, false);
        for (int i = 0; i < integrityAuditing.parity.length; i++) {
            osParities.write(integrityAuditing.parity[i]);
        }
        osParities.close();
        System.out.println("store tags in local");
        //cal Extra storage cost
        long extraStorageSize = uploadParities.length();
        System.out.println("extraStorageSize is " + extraStorageSize + " Bytes");

//TEST
      //  System.out.println("Original data");
        // System.out.println(JSON.toJSONString(integrityAuditing.originalData));

        //upload File and tags to COS
        long start_time_upload = System.nanoTime();
        CloudAPI cloudAPI = new CloudAPI( cosConfigFilePath);

        cloudAPI.uploadFile(uploadSourceFilePath, "sourceFile.txt");
        cloudAPI.uploadFile(uploadParitiesPath, "parities.txt");
        System.out.println("upload File and tags to COS");
        long end_time_upload = System.nanoTime();
        time[2] = end_time_upload-start_time_upload;
        System.out.println("---OutSource phase finished---");

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
        System.out.println("---Audit phase start---");
        long start_time_audit = System.nanoTime();
        ChallengeData challengeData = integrityAuditing.audit(460);
        long end_time_audit= System.nanoTime();
        time[3]=  end_time_audit - start_time_audit ;

        //write body , and send the challenge data to SCF and trigger SCF to start audit
        try (PrintWriter writer = new PrintWriter(connection.getOutputStream())) {
            Map<String, String> foo = new HashMap<>();
            foo.put("bucketName", "tpds-in-scf-bucket-1302225808");
            foo.put("regionName", "ap-guangzhou");
            foo.put("DATA_SHARDS", Integer.toString(DATA_SHARDS));
            foo.put("PARITY_SHARDS", Integer.toString((BLOCK_SHARDS - DATA_SHARDS)));
            writer.write(JSON.toJSONString(foo));
            writer.write(JSON.toJSONString(challengeData));
           // System.out.println("challengeData str:" + JSON.toJSONString(challengeData));
            writer.flush();
        }
        System.out.println("---Audit phase finished---");
        System.out.println("Waiting SCF return Proof for verifying");

        //read response
        String responseDataStr = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                //System.out.println("read from response:" + line);
                if (line.indexOf("body") != -1) { //只需要body的内容
                    responseDataStr = line;
                }

            }
        } finally {
            connection.disconnect();
        }
        //提取body 中序列化的proof data
      //  System.out.println("receive body str");
      //  System.out.println(responseDataStr);
        int indexOfDataProof = responseDataStr.indexOf("dataProof");
        String targetStr = responseDataStr.substring(indexOfDataProof - 2, responseDataStr.length() - 1);

        ProofData proofData = JSON.parseObject(targetStr, ProofData.class);
       // System.out.println("Get proofData content:" + proofData.dataProof.toString() + "\t and" + proofData.parityProof.toString());
        //将proofData 写到文件中，测量 communication cost
        String proofDataStoragePath = "E:\\Gitfolder\\tpds\\tpdsProofData\\proofData.txt";
        File proofDataCost = new File(proofDataStoragePath);
        proofDataCost.createNewFile();
        OutputStream osProofData = new FileOutputStream(proofDataCost, false);
        osProofData.write(proofData.parityProof);
        osProofData.write(proofData.dataProof);
        osProofData.close();
        //cal communication cost
        long proofDataSize = proofDataCost.length();
        System.out.println("proofDataSize is " + proofDataSize + " Bytes");


        //execute verify parse
        System.out.println("---Verify phase start---");
        long start_time_verify = System.nanoTime();
        if (integrityAuditing.verify(challengeData, proofData)) {
            System.out.println("---Verify phase finished---");
            System.out.println("The data is intact in the cloud.The auditing process is success!");
        }
        long end_time_verify = System.nanoTime();
        time[4] = end_time_verify - start_time_verify ;


        //store the performance in local
        String performanceFilePath = new String("E:\\Gitfolder\\tpds\\performanceResult\\result.txt");
        File performanceFile = new File(performanceFilePath);

        if(performanceFile.exists() && taskCount == 1){
            performanceFile.delete();
        }
        performanceFile.createNewFile();
        FileWriter resWriter = new FileWriter(performanceFile,true);

        String title = "Audit data size is "+ String.valueOf(sourceFileSize)+". No."+String.valueOf(taskCount)+" audit process. \r\n";
        resWriter.write(title);

        resWriter.write("StorageCost "+String.valueOf(extraStorageSize)+"  CommunicationCost "+String.valueOf(proofDataSize)+"\r\n");
        for(int i=0;i<5;i++){
            resWriter.write("time["+i+"] = " + String.valueOf(time[i])+"  ");
        }
        resWriter.write("\r\n");
       resWriter.close();
    }
}
