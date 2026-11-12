import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

import com.ibm.dbb.build.*
import com.ibm.dbb.build.internal.Messages
import com.ibm.dbb.build.smf.*

import groovy.cli.commons.*
import groovy.json.JsonOutput

def parseArgs(String[] cliArgs){
	def cli = new CliBuilder(usage: "readSMF.groovy [options]",header: "\nAvailable options\n")
	cli.h(longOpt: 'help','help')
	cli.q(longOpt: 'hlq',args:1,optionalArg:true,argName:'hlq','High level qualifier for working dataset.')
	cli.t(longOpt: 'type', args:1,optionalArg:true, argName:'type','SMF type to process. Can be a number or range (i.e. 0:255)')
	cli.d(longOpt: 'datasets', args:1, valueSeparator:',', optionalArg:true, argName:'datasets','Comma separated list of SMF datasets as defined in SYS1.PARMLIB(SMFPRMxx) DSNAME option')
	cli.l(longOpt: 'logstreams', args:1, valueSeparator:',', optionalArg:true, argName:'logstreams','Comma separated list of SMF log streams, when SMF records are recorded to log streams. Takes precedence over --datasets')
	cli.w(longOpt: 'workDir', args:1, argName:'dir', 'Path to the output directory for the logs and the report. Default is logs')
	cli.e(longOpt: 'logEncoding', args:1, argName:'encoding', 'Encoding of output logs. Default is EBCDIC')
	cli.j(longOpt: 'json', 'Also write the build metrics as JSON (SMFReport.json) in addition to the HTML report (SMFReport.html)')
	
	def opts = cli.parse(cliArgs)
	if(opts.h)
	{
		cli.usage()
		System.exit(0)
	}
	
	if(opts.q && opts.q instanceof Boolean)
	{
		throw new BuildException("-q needs you to specify a high level qualifier. Run with -h to view details.")
	}
	
	return opts
}


def loadProperties(OptionAccessor opts) {
	// check to see if there is a ./build.properties to load
	def properties = BuildProperties.getInstance()
	def scriptDir = new File(getClass().protectionDomain.codeSource.location.path).parent
	def buildPropFile = new File("$scriptDir/smf.properties")
	if (buildPropFile.exists())
		   BuildProperties.load(buildPropFile)

	// set command line arguments
	if (opts.q) properties.hlq = opts.q
	if (opts.t)
	{	
		if (opts.t instanceof Boolean)
			properties.recordType = "122"
		else
			properties.recordType = opts.t
	}
	if (opts.d) properties.datasets = opts.d
	if (opts.l) properties.logstreams = opts.l
	if (opts.w) properties.workDir = opts.w
	if (opts.e) properties.logEncoding = opts.e
	if (opts.j) properties.outputJson = "true"
	
	// Default the output directory when it was not set by the command line or by smf.properties
	if (!properties.workDir) properties.workDir = "logs"
	
	validateType(properties.recordType)
	
	println("Datasets: ${properties.datasets}")
	println("Log streams: ${properties.logstreams}")
	println("** Build properties at startup:")
	println(properties.list())

	return properties
}

def validateRequiredProperties(List<String> props) {
	def properties = BuildProperties.getInstance()
	props.each { prop ->
		assert properties."$prop" : "Missing property $prop"
	}
}

def validateType( String type )
{
	// if type is null...okay
	if ( !type )
		return true
		
	// if type is a number...also okay
	if ( type.isNumber() )
		return true

	// validate that all characters are acceptable
	if (!type.matches("[0-9,:\\s]+"))
	{	
			throw new BuildException("The record type parameter, ${type}, contains an invalid character. Valid characters are 0-9, comma(,), and colon(:).")
	}
	
	// check each comma separated segment for a number or number:number
	def segments = type.split(',') // treat as comma separated list
	segments.each{ segment ->
		if (!segment.isNumber() && !(segment.contains(':') && segment.split(':')[0].isNumber() && segment.split(':')[1].isNumber()))
		{
			throw new BuildException("The record type, ${segment}, is not a valid SMF record type. Please enter a single number or two numbers separated by a colon(:)")
		}
	}
}

// Determine if type is among the SMF types that were requested
def isSmfTypeInteresting( int type )
{
	// never interested in type 2 or 3. These only exist in dump dataset
	if (type == 2 || type == 3 )
		return false;
		
	def properties = BuildProperties.getInstance()
	
	// If no record type specified, accept all records
	if (properties.recordType == null )
		return true

	// If valid number...check to see if type is that number
	if (properties.recordType.isNumber())
		return (properties.recordType.toInteger() == type)

	// check each comma separated segment for a number or number:number
	def segments = properties.recordType.split(',') // treat as comma separated list
	for ( String segment: segments )
	{
		// If segment is a valid number...check to see if type is that number
		if (segment.isNumber() && segment.toInteger() == type)
			return true
			
		// If int:int type input, check range...inclusive
		if (segment.contains(':') && segment.split(':')[0].toInteger() <= type && segment.split(':')[1].toInteger() >= type)
			return true
	}
	
	return false	
}

def byteToHex(byte b)
{
   byte[] a = new byte[1];
   a[0] = b;
   return "0x"+a.encodeHex().toString().toUpperCase()
}

def bytesToHex(byte[] bytes, int offset, int length)
{
   byte[] newBytes = new byte[length];
   int i=0,j=offset
   while ( i < length )
   {
	  newBytes[i] = bytes[j]
	  i++
	  j++
   }
   return "0x"+newBytes.encodeHex().toString().toUpperCase()
}

def bytesToInt(byte[] bytes, int offset)
{
   ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, 4)
   return buffer.getInt()
}

def bytesToShort(byte[] bytes, int offset)
{
   ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, 2)
   return buffer.getShort()
}

def bytesToString(byte[] bytes, int offset, int length)
{
   byte[] newBytes = new byte[length];
   int i=0,j=offset
   while ( i < length )
   {
	  newBytes[i] = bytes[j]
	  i++
	  j++
   }
   return new String(newBytes, Utils.retrieveEncoding())
}

// Writes the report out to a file in the working directory. The report is HTML unless another file name is given.
def writeReport(StringBuffer content, String fileName = "SMFReport.html") throws BuildException
{
	def properties = BuildProperties.getInstance()
	
	def reportEncoding = "UTF-8"
	def file = new File("${properties.workDir}/${fileName}")
	println("writing report to $file")
	FileOutputStream fos = null
	try
	{
		file.getParentFile().mkdirs()
		fos = new FileOutputStream(file)
		fos.write(content.toString().getBytes(reportEncoding))
		fos.flush()
	}
	catch (IOException e)
	{
		throw new BuildException(Messages.getMessage("Error_CREATING_FILE", file.getAbsoluteFile(), e))
	}
	finally
	{
		if (fos != null)
			fos.close()
	}
}

// Converts the merged type 122 subtype 3 records of one build (a BuildMetricsSmfRecord.ReassembledMetrics)
// to a map. The layout of the build metrics follows the JSON of BuildMetricsImpl.toJsonString(), with the
// information about the SMF records that make up the build added.
def buildToMap( def build )
{
	def bm = build.getBuildMetrics()

	def buildMetrics = [
		applicationName      : bm.getApplicationName() ?: "",
		applicationRepository: bm.getApplicationRepository() ?: "",
		gitBranch            : bm.getGitBranch() ?: "",
		gitHash              : bm.getGitHash() ?: "",
		userId               : bm.getUserID() ?: "",
		hlq                  : bm.getHLQ() ?: "",
		buildId              : bm.getBuildID() ?: "",
		buildDurationMs      : bm.getBuildDuration(),
		buildLifecycle       : bm.getBuildLifecycle() ?: "",
		filesBuilt           : bm.getNumFilesBuilt(),
		buildStatus          : statusName(bm.getBuildStatus()).toUpperCase()
	]
	if (bm.getBuildStartTime() != null)
		buildMetrics.buildStartTime = bm.getBuildStartTime().toInstant().toString()
	if (bm.getBuildEndTime() != null)
		buildMetrics.buildEndTime = bm.getBuildEndTime().toInstant().toString()
	if (bm.getFailedTask() != null)
		buildMetrics.failedTask = bm.getFailedTask()
	if (bm.getFailureMessage() != null)
		buildMetrics.failureMessage = bm.getFailureMessage()

	def result = [
		correlationId : build.getCorrelationId(),
		totalRecords  : build.getTotalRecords(),
		complete      : build.isComplete(),
		missingRecords: build.getMissingSequenceNumbers(),
		buildMetrics  : buildMetrics
	]

	// Only impact builds have changed and impacted files
	if (bm.getNumFilesChanged() != null || bm.getNumFilesImpacted() != null)
	{
		def impact = [:]
		impact.filesChanged = bm.getNumFilesChanged()
		if (bm.getChangedFiles() != null)
			impact.changedFiles = bm.getChangedFiles()
		impact.filesImpacted = bm.getNumFilesImpacted()
		if (bm.getImpactedFiles() != null)
			impact.impactedFiles = bm.getImpactedFiles()
		result.impactAnalysisMetrics = impact
	}

	def taskMetrics = [:]
	def languageTaskMetrics = [:]
	bm.getTaskMetricsList().each{ tm ->
		if (tm instanceof com.ibm.dbb.build.smf.metrics.TaskMetricImpl)
			taskMetrics[tm.getName()] = tm.getDuration()
		else if (tm instanceof com.ibm.dbb.build.smf.metrics.LanguageTaskMetricsImpl)
			languageTaskMetrics[tm.getLanguage()] = new LinkedHashMap(tm.getFileDurations())
	}
	result.taskMetrics = taskMetrics
	result.languageTaskMetrics = languageTaskMetrics
	return result
}

// Writes the build metrics of all builds as JSON to SMFReport.json in the working directory
def writeJsonReport( def builds )
{
	def json = JsonOutput.prettyPrint(JsonOutput.toJson([builds: builds.collect{ buildToMap(it) }]))
	writeReport(new StringBuffer(json), "SMFReport.json")
}



// Adds HTML header, stype and body tags to report
def startReport( StringBuffer report)
{
	report.append("""<!DOCTYPE html>
<html>

<head>
	<style type="text/css">
		body {
    		font: 12px Arial, sans-serif;
		}

		table {
    		border-collapse: collapse;
		}

		table, th, td {
    		border: 1px solid #A9A9A9;
		}

		th, td {
    		padding: 10px;
    		text-align: left;
		}

		tr.even {
			background-color: #f2f2f2
		}

		th {
    		background-color: #81C4E8;
    		color: white;
		}

		td.label {
			font-weight: bold;
		}

		.bottomRight {
			float:right;
			text-align:right;
			font: 10px Arial, sans-serif;
		}

		.dep {
			font: 11px Arial, sans-serif;
		}

		.depType {
			font: 9px Arial, sans-serif;
			background-color: #81C4E8;
		}
	</style>
</head>

<body>
	<h2>SMF Report</h2>
	<p id="main"></p>""")
}

// Completes the report HTML
def endReport( StringBuffer report)
{
	report.append("""
</body>
</html>""")
}

// Start the Report table with headers. There is one row for each build.
def startReportTable( StringBuffer report)
{
	report.append("""
	<table border="1" cellpadding="4" summary="Build metrics">
		<tr>
			<th style="text-align:left">Application</th>
			<th style="text-align:left">Repository</th>
			<th style="text-align:left">Branch</th>
			<th style="text-align:left">Git Hash</th>
			<th style="text-align:left">Userid</th>
			<th style="text-align:left">HLQ</th>
			<th style="text-align:left">Build ID</th>
			<th style="text-align:left">Lifecycle</th>
			<th style="text-align:left">Status</th>
			<th style="text-align:left">Start Time</th>
			<th style="text-align:left">End Time</th>
			<th style="text-align:left">Duration (ms)</th>
			<th style="text-align:left">Files Built</th>
			<th style="text-align:left">Files Changed</th>
			<th style="text-align:left">Files Impacted</th>
			<th style="text-align:left">Failed Task</th>
			<th style="text-align:left">Failure Message</th>
			<th style="text-align:left">Records</th>
			<th style="text-align:left">Correlation ID</th>
		</tr>""")
}

// Completes the report table
def endReportTable( StringBuffer report)
{
	report.append("""
	</table>""")
}

// Escape text so that it can be put in the HTML report
def escapeHtml( Object value )
{
	if (value == null)
		return ""
	return value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

// Text for the DBB_Status value of the build metrics record
def statusName( long status )
{
	switch (status)
	{
		case 0: return "Clean"
		case 1: return "Error"
		case 2: return "Warning"
		case 4: return "Preview"
		default: return "Unknown (${status})"
	}
}

// Add the build level metrics of one build to the report table.
// build is a BuildMetricsSmfRecord.ReassembledMetrics: the type 122 subtype 3 records of a build merged together
def reportBuildMetrics( StringBuffer report, def build )
{
	def bm = build.getBuildMetrics()
	def records = "${build.getTotalRecords()}"
	if (!build.isComplete())
		records += " (missing ${build.getMissingSequenceNumbers().join(',')})"

	report.append("""
		<tr>
			<td>${escapeHtml(bm.getApplicationName())}</td>
			<td>${escapeHtml(bm.getApplicationRepository())}</td>
			<td>${escapeHtml(bm.getGitBranch())}</td>
			<td>${escapeHtml(bm.getGitHash())}</td>
			<td>${escapeHtml(bm.getUserID())}</td>
			<td>${escapeHtml(bm.getHLQ())}</td>
			<td>${escapeHtml(bm.getBuildID())}</td>
			<td>${escapeHtml(bm.getBuildLifecycle())}</td>
			<td>${statusName(bm.getBuildStatus())}</td>
			<td>${bm.getBuildStartTime() ?: ''}</td>
			<td>${bm.getBuildEndTime() ?: ''}</td>
			<td>${bm.getBuildDuration()}</td>
			<td>${bm.getNumFilesBuilt()}</td>
			<td>${bm.getNumFilesChanged() != null ? bm.getNumFilesChanged() : ''}</td>
			<td>${bm.getNumFilesImpacted() != null ? bm.getNumFilesImpacted() : ''}</td>
			<td>${escapeHtml(bm.getFailedTask())}</td>
			<td>${escapeHtml(bm.getFailureMessage())}</td>
			<td>${escapeHtml(records)}</td>
			<td>${escapeHtml(build.getCorrelationId())}</td>
		</tr>""")
}

// Add the task durations, the file durations of each language, and the changed and impacted files of one build
def reportBuildMetricsDetails( StringBuffer report, def build )
{
	def bm = build.getBuildMetrics()
	report.append("""
	<h3>Build ${escapeHtml(bm.getBuildID())} (${escapeHtml(bm.getApplicationName())})</h3>""")

	// One table for all tasks. A language task is shown as Language(name) and its duration cell is a
	// nested table with the duration of each file of that language.
	def taskMetrics = bm.getTaskMetricsList().findAll{
		it instanceof com.ibm.dbb.build.smf.metrics.TaskMetricImpl || it instanceof com.ibm.dbb.build.smf.metrics.LanguageTaskMetricsImpl
	}
	if (taskMetrics)
	{
		report.append("""
	<table border="1" cellpadding="4" summary="Task durations">
		<tr>
			<th style="text-align:left">Task</th>
			<th style="text-align:left">Duration (ms)</th>
		</tr>""")
		taskMetrics.each{ task ->
			if (task instanceof com.ibm.dbb.build.smf.metrics.LanguageTaskMetricsImpl)
			{
				// The cell has no padding so the nested table fills the whole duration column. The outer border
				// of the nested table is hidden so that it does not double up with the border of the cell.
				report.append("""
		<tr>
			<td>${escapeHtml("Language(${task.getLanguage()})")}</td>
			<td style="padding:0">""")
				if (!task.getFileDurations().isEmpty())
				{
					report.append("""
				<table style="width:100%; border-collapse:collapse; border-style:hidden" summary="File durations of ${escapeHtml(task.getLanguage())}">""")
					task.getFileDurations().each{ file, duration ->
						report.append("""
					<tr>
						<td>${escapeHtml(file)}</td>
						<td>${duration}</td>
					</tr>""")
					}
					report.append("""
				</table>""")
				}
				report.append("""</td>
		</tr>""")
			}
			else
			{
				report.append("""
		<tr>
			<td>${escapeHtml(task.getName())}</td>
			<td>${task.getDuration()}</td>
		</tr>""")
			}
		}
		report.append("""
	</table>""")
	}

	if (bm.getChangedFiles() != null || bm.getImpactedFiles() != null)
	{
		report.append("""
	<br/>
	<table border="1" cellpadding="4" summary="Changed and impacted files">
		<tr>
			<th style="text-align:left">Impact Analysis</th>
			<th style="text-align:left">File</th>
		</tr>""")
		bm.getChangedFiles()?.each{ file ->
			report.append("""
		<tr>
			<td>Changed</td>
			<td>${escapeHtml(file)}</td>
		</tr>""")
		}
		bm.getImpactedFiles()?.each{ file ->
			report.append("""
		<tr>
			<td>Impacted</td>
			<td>${escapeHtml(file)}</td>
		</tr>""")
		}
		report.append("""
	</table>""")
	}
}
	