package com.fchen_group.TPDSInScf.Core;
/**
 * This class is used to store proof data,including two parts:dataProof and parityProof
 * both of them are calculated as a combined form by SCF
 * */
public class ProofData {
    public byte [] dataProof;
    public byte [] parityProof;
    public ProofData(byte[] dataProof,byte[] parityProof){
        this.dataProof = dataProof;
        this.parityProof = parityProof;
    }
}
