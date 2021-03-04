package com.fchen_group.TPDSInScf.Core;

public class ProofData {
    public byte [] dataProof;
    public byte [] parityProof;
    public ProofData(byte[] dataProof,byte[] parityProof){
        this.dataProof = dataProof;
        this.parityProof = parityProof;
    }
}
