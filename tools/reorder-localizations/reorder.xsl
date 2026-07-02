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
    <xsl:variable name="referenceNode" select="$referenceDoc/*[@name = current()/@name][1]"/>

    <xsl:element name="{name()}">
      <xsl:attribute name="name">
        <xsl:value-of select="@name"/>
      </xsl:attribute>

      <xsl:choose>
        <xsl:when test="self::string and $referenceNode/@formatted">
          <xsl:attribute name="formatted">
            <xsl:value-of select="$referenceNode/@formatted"/>
          </xsl:attribute>
        </xsl:when>
        <xsl:when test="self::string and not($referenceNode) and @formatted">
          <xsl:attribute name="formatted">
            <xsl:value-of select="@formatted"/>
          </xsl:attribute>
        </xsl:when>
      </xsl:choose>

      <xsl:copy-of select="@*[name() != 'name' and name() != 'formatted']"/>
      <xsl:copy-of select="node()"/>
    </xsl:element>
  </xsl:template>
</xsl:stylesheet>
