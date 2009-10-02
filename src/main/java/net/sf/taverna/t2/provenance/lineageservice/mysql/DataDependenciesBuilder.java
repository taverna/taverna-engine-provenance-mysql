/**
 * 
 */
package net.sf.taverna.t2.provenance.lineageservice.mysql;

import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.taverna.t2.provenance.lineageservice.utils.DDRecord;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

/**
 * this code populates the DD table in the T2Provenance DB. The table is used to
 * test the naive lineage query algorithm. The table contains all pairwise data
 * dependencies between input and output variables for a given processor, at the
 * level of individual element within a list -- and for each processor in the
 * graph. <br/>
 * This works offline, using the log of the iteration events generated by the
 * EventsProcessor
 * 
 * @author paolo
 * 
 */
public class DataDependenciesBuilder {

	private MySQLProvenanceWriter pw = null;
	private MySQLProvenanceQuery pq = null;

	private static final String EVENTS_LOG_DIR = "/tmp/TEST-EVENTS";

	public final static void main(String[] args) {

		String DB_URL_LOCAL = "localhost"; // URL of database server //$NON-NLS-1$
		String DB_USER = "paolo"; // database user id //$NON-NLS-1$
		String DB_PASSWD = "riccardino"; //$NON-NLS-1$

		String location = DB_URL_LOCAL
				+ "/T2Provenance?user=" + DB_USER + "&password=" + DB_PASSWD; //$NON-NLS-1$ //$NON-NLS-2$

		String jdbcString = "jdbc:mysql://" + location;

		String clearDB = "false"; // testFiles.getString("clearDB");

		DataDependenciesBuilder ddBuilder = new DataDependenciesBuilder(
				jdbcString, clearDB);

		try {
			ddBuilder.buildDD();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();

		}
	}

	public DataDependenciesBuilder(String location, String clearDB) {

		pw = new MySQLProvenanceWriter();
		
		pq = new MySQLProvenanceQuery();
		

		if (clearDB.equals("true")) {
			System.out.println("clearing DD DB");
			pw.clearDD();
		} else {
			System.out.println("NOT clearing DD DB");
		}

	}

	public void buildDD() throws SQLException {

		// fetch latest WFInstance ID, to use as part of the key
		List<String> IDs = pq.getWFInstanceIDs();

		String wfInstanceID = IDs.get(0);

		// read all iteration events from the EVENTS log

		File dir = new File(EVENTS_LOG_DIR);

		FilenameFilter filter = new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.contains("iteration");
			}
		};

		String[] children = dir.list(filter);

		System.out.println("about to process " + children.length + " events");
		if (children == null) {
			// Either dir does not exist or is not a directory
			System.out.println("no files retrieved -- terminating");
			return;
		} else {
			// System.out.println("iteration events:");
			for (int i = 0; i < children.length; i++) {
				// Get filename of file or directory
				String filename = children[i];
				// System.out.println(filename);

				processEvent(children[i], wfInstanceID);

			}

			// fill in the table with P1:Y -> P2:X dependencies
			System.out.println("*** fillXferSteps starting for wfInstanceID = "
					+ wfInstanceID);
			fillXferSteps(wfInstanceID);

		}
	}

	private void fillXferSteps(String wfInstanceID) throws SQLException {

		// ////////////
		// set the run instances (scope)
		// ////////////
		// String wfInstance = null; // TODO only support one instance query at
		// this time
		// ArrayList<String> instances = (ArrayList<String>)
		// pq.getWFInstanceIDs(); // ordered by timestamp
		//
		// if (instances.size()>0) {
		// wfInstance = instances.get(0);
		// System.out.println("instance "+wfInstance);
		// } else {
		// System.out.println("FATAL: no wfinstances in DB -- terminating");
		// }

		// select distinct (P, vTo, valTo) and iterate on them
		Set<DDRecord> fromRecords = pq.queryAllFromValues(wfInstanceID);

		System.out.println("processing " + fromRecords.size() + " fromValues");

		for (DDRecord fromRecord : fromRecords) {

			// System.out.println("processing from-record "+fromRecord.getPFrom()+" "+fromRecord.getVFrom()+" "+fromRecord.getValFrom());

			// find all arcs that have this sink P:V
			Set<DDRecord> toRecords = pq.queryArcsForDD(fromRecord.getPFrom(),
					fromRecord.getVFrom(), fromRecord.getValFrom(),
					wfInstanceID);

			for (DDRecord toRecord : toRecords) {
				// System.out.println("to record: "+toRecord.getPTo()+" "+toRecord.getVTo()+" "+toRecord.getValTo());

				pw.writeDDRecord(toRecord.getPTo(), toRecord.getVTo(), toRecord
						.getValTo(), fromRecord.getPFrom(), fromRecord
						.getVFrom(), fromRecord.getValFrom(), null,
						wfInstanceID);
			}
		}

	}

	private void processEvent(String eventFilename, String wfInstanceID) {

		// FileReader fr = new FileReader(new
		// File(EVENTS_LOG_DIR+"/"+filename));

		// parse the event into DOM
		SAXBuilder b = new SAXBuilder();
		Document d;

		Map<String, String> inputs = new HashMap<String, String>();
		Map<String, String> outputs = new HashMap<String, String>();

		try {
			d = b.build(new FileReader(new File(EVENTS_LOG_DIR + "/"
					+ eventFilename)));
			Element root = d.getRootElement();
			String processID = root.getAttributeValue("processID"); // this is
																	// the
																	// external
																	// process
																	// ID
			String iteration = root.getAttributeValue("id");

			// this has the weird form facade0:dataflowname:pname need to
			// extract pname from here
			String[] processName = processID.split(":");
			String processor = processName[processName.length - 1]; // 3rd
																	// component
																	// of
																	// composite
																	// name

			String itVector = root.getAttributeValue("id");
			Element inputDataEl = root.getChild("inputdata");
			Element outputDataEl = root.getChild("outputdata");

			// process input data
			List<Element> inputPorts = inputDataEl.getChildren("port");

			for (Element inputport : inputPorts) {
				String portName = inputport.getAttributeValue("name");

				// value type may vary
				List<Element> valueElements = inputport.getChildren(); // hopefully
				if (valueElements != null && valueElements.size() > 0) {

					Element valueEl = valueElements.get(0); // expect only 1
															// child

					inputs.put(portName, valueEl.getAttributeValue("id"));
				}
			}

			// processOutput
			List<Element> outputPorts = outputDataEl.getChildren("port");

			for (Element outputport : outputPorts) {

				String portName = outputport.getAttributeValue("name");

				// value type may vary
				List<Element> valueElements = outputport.getChildren();
				if (valueElements != null && valueElements.size() > 0) {

					Element valueEl = valueElements.get(0); // only really 1
															// child

					outputs.put(portName, valueEl.getAttributeValue("id"));
				}
			}

			// construct tuples
			for (Map.Entry<String, String> input : inputs.entrySet()) {

				for (Map.Entry<String, String> output : outputs.entrySet()) {

					// System.out.println("DD tuple: "+processor+
					// " "+input.getKey()+
					// " "+input.getValue()+
					// " "+output.getKey()+
					// " "+output.getValue()+
					// " "+iteration);

					// insert into DD table in provenance DB
					pw.writeDDRecord(processor, input.getKey(), input
							.getValue(), processor, output.getKey(), output
							.getValue(), iteration, wfInstanceID);
				}

			}

		} catch (JDOMException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

}
