package com.fchen_group.TPDSInScf.Run;

import java.io.IOException;
import java.util.Scanner;

public class Benchmark {
    public static void main(String args[]) throws IOException {
        String filePath = "E:\\Gitfolder\\tpds\\tpdsTestData\\randomFile10M.rar";
        int BLOCK_SHARDS = 255;
        int DATA_SHARDS = 223;
        System.out.println("The ReedSolomon parameters: (BLOCK_SHARDS, DATA_SHARDS)=(" + BLOCK_SHARDS + "," + DATA_SHARDS + ")");
        for(int i=1; i<=10 ; i++){
            System.out.println("*** NO."+i+" Audit Task ***");
            Client.auditTask(filePath, BLOCK_SHARDS, DATA_SHARDS,i);
        }
        System.out.println("Benchmark finished!");
    }


}
