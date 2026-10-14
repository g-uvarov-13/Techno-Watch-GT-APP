package app.gtdial.lab;

/** A verified watch identity. Unknown identities deliberately carry no invented visual data. */
final class CurrentDialSnapshot {
    final String alias, catalogId, previewAsset;
    final String localArtifactSha256;
    final String[] displayFields;
    final long receivedAtMs;
    private CurrentDialSnapshot(String alias,String catalogId,String previewAsset,String[] displayFields,long receivedAtMs){this(alias,catalogId,previewAsset,displayFields,receivedAtMs,null);}
    private CurrentDialSnapshot(String alias,String catalogId,String previewAsset,String[] displayFields,long receivedAtMs,String artifact){this.alias=alias;this.catalogId=catalogId;this.previewAsset=previewAsset;this.displayFields=displayFields==null?null:displayFields.clone();this.receivedAtMs=receivedAtMs;localArtifactSha256=artifact;}
    static CurrentDialSnapshot unknown(String alias,long receivedAtMs){return new CurrentDialSnapshot(alias,null,null,null,receivedAtMs);}
    static CurrentDialSnapshot known(String alias,String catalogId,String previewAsset,String[] displayFields,long receivedAtMs){if(alias==null||alias.isEmpty()||catalogId==null||catalogId.isEmpty())throw new IllegalArgumentException("identity");return new CurrentDialSnapshot(alias,catalogId,previewAsset,displayFields,receivedAtMs);}
    boolean isKnown(){return catalogId!=null;}
    static CurrentDialSnapshot local(String alias,String name,String artifact,String[] fields,long receivedAtMs){if(artifact==null||!artifact.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("artifact");return new CurrentDialSnapshot(alias,name,null,fields,receivedAtMs,artifact);}
    boolean isLocal(){return localArtifactSha256!=null;}
}
