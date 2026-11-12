# ReadSMFRecords
These sample scripts read System Management Facilities (SMF) records using IBM's Dependency Based Build capabilities. The script can read and process many types
of SMF records. However, only the DBB Build Metrics SMF record (Type:122, subType:3) is fully parsed and recorded in the report. Only the header portion of other types will be recorded in the report. See [DBB SMF support](https://www.ibm.com/docs/en/adffz/dbb/3.0.x?topic=customization-enabling-system-management-facility-smf-support) for more details.

## Configuring
The smf.properties is loaded by the read SMF script to provide default values for several parameters. Script parameters will override these values.
* hlq - High level qualifier for a working data set. The script will dump the SMF records to this data set and then read and process the records.
* recordType - The SMF record type to process. This can be a single number or a range (i.e. 0:255).
* datasets - The SMF datasets, a comma separated list, as defined in SYS1.PARMLIB(SMFPRMxx) DSNAME option. Use this when SMF records are recorded to SMF data sets. The script dumps them with IFASMFDP.
* logstreams - The SMF log streams, a comma separated list, as defined in SYS1.PARMLIB(SMFPRMxx) LSNAME option. Use this when SMF records are recorded to log streams. The script dumps them with IFASMFDL. If both logstreams and datasets are set, logstreams is used.
* outputJson - Set to true to write the build metrics as JSON (SMFReport.json) instead of an HTML table (SMFReport.html). Default is false.

Provide either datasets or logstreams. The rest of the processing is the same for both.
 
### ReadSMF.groovy
This script allows a user to read SMF records, specifically the DBB SMF record. The supported options are:
<table>
<tr>
 <td valign="top">-d,--datasets &lt;datasets&gt;</td><td valign="top">Comma separated list of SMF datasets as defined in SYS1.PARMLIB(SMFPRMxx) DSNAME option</td>
</tr>
<tr>
 <td valign="top">-e,--logEncoding &lt;encoding&gt;</td><td valign="top">Encoding of output logs. Default is EBCDIC</td>
</tr>
<tr>
 <td valign="top">-h,--help</td><td valign="top">help</td>
</tr>
<tr>
 <td valign="top">-j,--json</td><td valign="top">Write the build metrics as JSON (SMFReport.json) instead of an HTML table (SMFReport.html)</td>
</tr>
<tr>
 <td valign="top">-l,--logstreams &lt;logstreams&gt;</td><td valign="top">Comma separated list of SMF log streams, when SMF records are recorded to log streams. Takes precedence over --datasets</td>
</tr>
<tr>
 <td valign="top">-q,--hlq &lt;hlq&gt;</td><td valign="top">High level qualifier for working dataset.</td>
</tr>
<tr>
 <td valign="top">-t,--type &lt;type&gt;</td><td valign="top">SMF type to process. Can be a number or range (i.e. 0:255)</td>
</tr>
<tr>
 <td valign="top">-w,--workDir &lt;dir&gt;</td><td valign="top">Path to the output directory for the logs and the report. Default is <code>logs</code>, relative to the current directory</td>
</tr>
</table>
