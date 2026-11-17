package com.aurammods.paracraft.core.openai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;

/** Verify the actual OIDC signature and binding before saving or activating an account. */
final class IdTokenValidator {
    static JsonObject verify(String token,JsonObject keys,String issuer,String clientId,String nonce,Instant now)throws Exception{
        String[] parts=token.split("\\.",-1);if(parts.length!=3)throw new GeneralSecurityException("Invalid OpenAI ID token");
        JsonObject header=decode(parts[0]),claims=decode(parts[1]);if(!"RS256".equals(OpenAiHttp.string(header,"alg","")))throw new GeneralSecurityException("Unsupported OpenAI ID-token signature algorithm");
        String kid=OpenAiHttp.string(header,"kid","");JsonObject selected=null;
        for(var item:keys.getAsJsonArray("keys")){var key=item.getAsJsonObject();if(kid.equals(OpenAiHttp.string(key,"kid","")) && "RSA".equals(OpenAiHttp.string(key,"kty","")) && (!key.has("use") || "sig".equals(key.get("use").getAsString())) && (!key.has("alg") || "RS256".equals(key.get("alg").getAsString())))selected=key;}
        if(selected==null)throw new GeneralSecurityException("OpenAI ID-token signing key was not found");
        var decoder=Base64.getUrlDecoder();var key=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1,decoder.decode(selected.get("n").getAsString())),new BigInteger(1,decoder.decode(selected.get("e").getAsString()))));
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initVerify(key);signature.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));if(!signature.verify(decoder.decode(parts[2])))throw new GeneralSecurityException("OpenAI ID-token signature did not verify");
        boolean audience=claims.has("aud") && (claims.get("aud").isJsonArray()?java.util.stream.StreamSupport.stream(claims.getAsJsonArray("aud").spliterator(),false).anyMatch(v->clientId.equals(v.getAsString())):clientId.equals(claims.get("aud").getAsString()));
        if(!issuer.equals(OpenAiHttp.string(claims,"iss","")) || !audience || !claims.has("exp") || claims.get("exp").getAsLong()<=now.getEpochSecond() || OpenAiHttp.string(claims,"sub","").isBlank())throw new GeneralSecurityException("OpenAI ID-token identity, audience or expiry did not validate");
        if(claims.has("iat") && claims.get("iat").getAsLong()>now.getEpochSecond()+60)throw new GeneralSecurityException("OpenAI ID token is dated in the future");
        if(nonce!=null && !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8),OpenAiHttp.string(claims,"nonce","").getBytes(StandardCharsets.UTF_8)))throw new GeneralSecurityException("OpenAI ID-token nonce did not validate");return claims;
    }
    private static JsonObject decode(String text){return JsonParser.parseString(new String(Base64.getUrlDecoder().decode(text),StandardCharsets.UTF_8)).getAsJsonObject();}
}
