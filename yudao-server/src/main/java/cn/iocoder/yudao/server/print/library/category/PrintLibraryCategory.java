package cn.iocoder.yudao.server.print.library.category;

/** Null count/sort explicitly means not established; do not fabricate file statistics. */
public record PrintLibraryCategory(String id,String version,String name,Long fileCount,Integer sort) {}
