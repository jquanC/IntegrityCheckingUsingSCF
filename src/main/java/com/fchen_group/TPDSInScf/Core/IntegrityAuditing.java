package com.fchen_group.TPDSInScf.Core;

import com.fchen_group.TPDSInScf.Utils.ReedSolomon.Galois;
import com.fchen_group.TPDSInScf.Utils.ReedSolomon.ReedSolomon;
import com.tencentcloudapi.ocr.v20181119.models.VehicleRegCertInfo;
import com.tencentcloudapi.tsf.v20180326.models.GatewayGroupIds;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

/**
 * This class implements each process of the auditing lifecycle
 */

public class IntegrityAuditing {
    private int DATA_SHARDS; //num of message bytes ,  223 in this protocol
    private int PARITY_SHARDS; //num of ecc parity bytes , 32 in this proto;
    private int SHARD_NUMBER; // the num of blocks


    private long fileSize;
    public final int BYTES_IN_INT = 4;
    private long storeSize;
    private String Key; //
    private String sKey;
    private String filePath;

    public byte[][] originalData;//the source data,stored as blocks
    public byte[][] parity; // the final calculated parity
    public int len = 16; //16 means the key has 16 chars, one chars=8bit, the key's security level is satisfied 128 bit

    /**
     * Construction method , used in SCF
     */
    public IntegrityAuditing(int DATA_SHARDS, int PARITY_SHARDS) {
        this.DATA_SHARDS = DATA_SHARDS;
        this.PARITY_SHARDS = PARITY_SHARDS;
    }

    /**
     * Construction method , used in client
     */
    public IntegrityAuditing(String filePath, int BLOCK_SHARDS, int DATA_SHARDS) throws IOException {

        this.filePath = filePath;
        this.DATA_SHARDS = DATA_SHARDS;
        this.PARITY_SHARDS = BLOCK_SHARDS - DATA_SHARDS;

        //cal SHARD_NUMBER
        File inputFile = new File(filePath);
        this.fileSize = inputFile.length();
        this.storeSize = fileSize + BYTES_IN_INT;
        this.SHARD_NUMBER = (Integer.parseInt(String.valueOf(storeSize)) + DATA_SHARDS - 1) / DATA_SHARDS;

        // read original data
        this.originalData = new byte[SHARD_NUMBER][DATA_SHARDS];
        FileInputStream in = new FileInputStream(inputFile);
        for (int i = 0; i < SHARD_NUMBER; i++) {
            in.read(originalData[i]);
        }
        in.close();

    }

    /**
     * Used to generate two secret key
     */
    public void genKey() {
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

        StringBuffer sBuffer1 = new StringBuffer();
        Random random1 = new Random();
        for (int i = 0; i < len; i++) {
            sBuffer1.append(chars.charAt(random1.nextInt(chars.length())));
        }

        StringBuffer sBuffer2 = new StringBuffer();
        Random random2 = new Random();
        for (int i = 0; i < this.PARITY_SHARDS; i++) {
            sBuffer2.append(chars.charAt(random2.nextInt(chars.length())));
        }
        this.Key = sBuffer1.toString();
        this.sKey = sBuffer2.toString();
    }

    /**
     * Calculate the tags of the source data
     */
    public long outSource() {

        long start_time_process = System.nanoTime();

        this.parity = new byte[SHARD_NUMBER][];
        ReedSolomon reedSolomon = new ReedSolomon(DATA_SHARDS, PARITY_SHARDS);
        for (int i = 0; i < SHARD_NUMBER; i++) {
            parity[i] = reedSolomon.encodeParity(originalData[i], 0, 1);

        }
        //Multiply a sKey num
        for (int i = 0; i < parity.length; i++) {
            byte[] sKeyBytes = sKey.getBytes();
            if (sKeyBytes.length != parity[i].length) {
                System.out.println("Error:  sKeyBytes.length != parity.length");
            } else {
                for (int j = 0; j < parity[i].length; j++) {
                    parity[i][j] = Galois.multiply(parity[i][j], sKeyBytes[j]);
                }
            }
        }
        //Add a pseudo random num
        for (int i = 0; i < parity.length; i++) {
            byte[] randoms = PseudoRandom.generateRandom(i, this.Key, PARITY_SHARDS);
            for (int j = 0; j < PARITY_SHARDS; j++) {
                parity[i][j] = Galois.add(parity[i][j], randoms[j]);
            }
        }
        long end_time_process = System.nanoTime();
        long timeProcessData = end_time_process - start_time_process;
        System.out.println("Process phase finished");
        return timeProcessData;
    }

    /**
     * Generate the challenge data for auditing
     */
    public ChallengeData audit(int challengeLen) {
        byte[] coefficients = new byte[challengeLen];
        int[] index = new int[challengeLen];

        Random random = new Random();
        for (int i = 0; i < challengeLen; i++) {
            index[i] = random.nextInt(SHARD_NUMBER);
        }

        random.nextBytes(coefficients);
        return new ChallengeData(index, coefficients);
    }

    /**
     * Calculate the proofDate after receiving the challenge data and retrieve data from the cloud
     *
     * @param challengeData
     * @param downloadData   the challenged source data
     * @param downloadParity the challenged parity data
     */
    public ProofData prove(ChallengeData challengeData, byte[][] downloadData, byte[][] downloadParity) {
        byte[] dataProof = new byte[DATA_SHARDS];
        byte[] parityProof = new byte[PARITY_SHARDS];

        for (int i = 0; i < challengeData.index.length; i++) {
            byte[] tempData = new byte[DATA_SHARDS];
            byte[] tempParity = new byte[PARITY_SHARDS];
            //int index = challengeData.index[i];

            //cal product of each selected parity block ,data block with coefficients
            for (int j = 0; j < PARITY_SHARDS; j++) {
                tempParity[j] = Galois.multiply(challengeData.coefficients[i], downloadParity[i][j]);
            }
            for (int j = 0; j < DATA_SHARDS; j++) {
                tempData[j] = Galois.multiply(challengeData.coefficients[i], downloadData[i][j]);
            }

            //cal the cumulative sum of calculated block
            for (int j = 0; j < PARITY_SHARDS; j++) {
                parityProof[j] = Galois.add(parityProof[j], tempParity[j]);
            }
            for (int j = 0; j < DATA_SHARDS; j++) {
                dataProof[j] = Galois.add(dataProof[j], tempData[j]);
            }
        }
        ProofData proofData = new ProofData(dataProof, parityProof);
        return proofData;

    }

    /**
     * To calculate the integrity audit result
     * @param  challengeData
     * @param proofData  proofData return by SCF*/
    public boolean verify(ChallengeData challengeData, ProofData proofData) {
        byte[] verifyParity = new byte[PARITY_SHARDS];
        byte[] reCalParity;

        //First, calculate the sum of the (coefficient * random numbers) in the verification equation
        byte[] sumTemp = new byte[PARITY_SHARDS];
        for (int i = 0; i < challengeData.index.length; i++) {
            byte[] AESRandomByte = PseudoRandom.generateRandom(challengeData.index[i], this.Key, PARITY_SHARDS);
            byte[] temp = new byte[PARITY_SHARDS];
            //cal each (c_j)*(F(i_j))
            for (int j = 0; j < PARITY_SHARDS; j++) {
                temp[j] = Galois.multiply(challengeData.coefficients[i], AESRandomByte[j]);
            }
            //cal sum
            for (int k = 0; k < PARITY_SHARDS; k++) {
                sumTemp[k] = Galois.add(sumTemp[k], temp[k]);
            }
        }
        //continue to cal the Ecc parity from (challengeData , ProofData)
        //firstly,execute: parity - HATags
        for (int i = 0; i < PARITY_SHARDS; i++) {
            verifyParity[i] = Galois.subtract(proofData.parityProof[i], sumTemp[i]);
        }
        //divided by the secret key s
        for (int j = 0; j < PARITY_SHARDS; j++) {
            byte[] sKeyBytes = sKey.getBytes();
            verifyParity[j] = Galois.divide(verifyParity[j], sKeyBytes[j]);
        }
        //using proofData to re cal Ecc parity for verify comparision
        ReedSolomon reedSolomon = new ReedSolomon(DATA_SHARDS, PARITY_SHARDS);
        reCalParity = reedSolomon.encodeParity(proofData.dataProof, 0, 1);

        return compareByteArray(verifyParity, reCalParity);

    }

    /**
     * To calculate tow byte[] array is equal or not according to the content
     * */
    private boolean compareByteArray(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        } else if (a.length != b.length) {
            return false;
        } else {
            if (!Arrays.equals(a, b)) {
                return false;
            }
            return true;
        }
    }
}



