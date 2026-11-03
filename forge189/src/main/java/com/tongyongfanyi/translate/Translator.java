package com.tongyongfanyi.translate;

/**
 * 翻译后端接口。
 */
public interface Translator {
    String translate(String text, String sourceLang, String targetLang) throws Exception;
}
