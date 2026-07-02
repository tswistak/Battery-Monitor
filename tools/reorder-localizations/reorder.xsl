<?xml version="1.0" encoding="utf-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output method="xml" encoding="utf-8" indent="yes"/>
  <xsl:strip-space elements="*"/>
  <xsl:param name="reference"/>
  <xsl:variable name="referenceDoc" select="document($reference)/resources"/>

  <xsl:template match="/resources">
    <xsl:copy>
      <xsl:copy-of select="@*"/>
      <xsl:apply-templates select="*[@name]">
        <xsl:sort select="not($referenceDoc/*[@name = current()/@name])"/>
        <xsl:sort
          select="count($referenceDoc/*[@name = current()/@name]/preceding-sibling::*[@name])"
          data-type="number"
        />
        <xsl:sort
          select="count(preceding-sibling::*[@name])"
          data-type="number"
        />
      </xsl:apply-templates>
    </xsl:copy>
  </xsl:template>

  <xsl:template match="*[@name]">
    <xsl:copy-of select="."/>
  </xsl:template>
</xsl:stylesheet>
